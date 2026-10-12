#!/usr/bin/env python3
"""GymOps gate bridge: connects a card / QR / fingerprint reader and a turnstile relay at the gym to GymOps.

Runs on a small PC or Raspberry Pi next to the gate (Python 3.10+, standard library only). It

  * reads scans from one or more INPUTS:
      keyboard     USB RFID / QR readers that "type" the card number or QR text followed by Enter (stdin);
                   operator commands start with "!" (!exit, !ack <id> <templateRef>, !fail <id>, !status)
      http         a local HTTP endpoint for fingerprint / face terminals or vendor middleware:
                   POST /scan {"credentialType":"FINGERPRINT","credentialValue":"<template ref>","direction":"ENTRY"}
                   POST /event {"event":"EXIT_BUTTON"}  (also /exit-button, /alarm, /commands/<id>/ack, GET /status)
      exit-button  a USB push button that sends Enter: every line on stdin is one exit-button press
  * asks GymOps for every decision over HTTPS (requests signed exactly like scripts/gate_simulator.py; the server's
    response signature is verified, so the gate only opens on a genuine GRANTED),
  * drives the RELAY (print | command | http) to open the turnstile, and optionally a deny signal (LED / beep),
  * keeps working when the internet drops: decides locally from the signed offline allowlist (cached on disk),
    buffers the events in a JSONL file and uploads them with /events/batch when the connection returns,
  * sends heartbeats, downloads the allowlist periodically, polls enrolment / delete commands and forwards them.

Quick start:
    export GATE_DEVICE_SECRET=...            # shown once in Access control -> Devices -> Register device
    python3 scripts/gate_bridge.py --config gate_bridge.json --self-test
    python3 scripts/gate_bridge.py --config gate_bridge.json

See scripts/gate_bridge.example.json for every setting and docs/SETUP_GUIDE.md ("How the gate vendor connects")
for the full device protocol.

Signature: X-Signature = hex(HMAC-SHA256(secret, METHOD|PATH|X-Timestamp|X-Nonce|hex(SHA-256(body)))), X-Timestamp in
epoch seconds. Responses: X-Signature = hex(HMAC(secret, "response|" + nonce + "|" + hex(SHA-256(body)))).
"""

from __future__ import annotations

import argparse
import copy
import datetime as dt
import hashlib
import hmac
import http.server
import json
import logging
import os
import queue
import re
import secrets
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

try:
    from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
except ImportError:  # pragma: no cover - Python < 3.9
    ZoneInfo = None  # type: ignore[assignment]
    ZoneInfoNotFoundError = Exception  # type: ignore[assignment,misc]

VERSION = "1.0"
GATE_PREFIX = "/api/v1/access/gate"
CREDENTIAL_TYPES = ("RFID_CARD", "FINGERPRINT", "FACE", "QR")
DIRECTIONS = ("ENTRY", "EXIT")
ALARM_TYPES = ("DOOR_FORCED", "DOOR_HELD_OPEN", "TAILGATING", "TAMPER")
MAX_BATCH = 500                      # ScanBatchRequest @Size(max = 500)
SKEW_TOLERANCE = dt.timedelta(minutes=5)

log = logging.getLogger("gate_bridge")

# ---------------------------------------------------------------------------------------------------------- defaults

DEFAULT_CONFIG: dict[str, Any] = {
    "server_url": "http://localhost",
    "device_id": None,
    "device_secret_env": "GATE_DEVICE_SECRET",
    "device_secret_file": None,
    "direction": None,
    "state_dir": "gate-bridge-state",
    "request_timeout_seconds": 4,
    "scan_retries": 1,
    "heartbeat_seconds": 60,
    "commands_poll_seconds": 15,
    "allowlist_refresh_seconds": 3600,
    "upload_interval_seconds": 30,
    "default_open_seconds": 5,
    "firmware_version": "gymops-gate-bridge/" + VERSION,
    "inputs": ["keyboard"],
    "keyboard": {
        "qr_prefix": "GQ1.",
        "fingerprint_prefix": "FP:",
        "face_prefix": "FACE:",
        "exit_keyword": "EXIT",
        "card_format": "hex",
        "reverse_card_bytes": False,
        "direction": None,
    },
    "http": {
        "bind": "127.0.0.1",
        "port": 8765,
        "token_env": "GATE_BRIDGE_HTTP_TOKEN",
        "drive_relay": True,
    },
    "relay": {"type": "print"},
    "deny_signal": {"type": "print"},
    "offline": {
        "enabled": True,
        "open_seconds": 5,
        "allow_unknown_exit": True,
        "exit_button_opens": True,
    },
    "commands": {"forward_url": None},
    "log_level": "INFO",
}


class ConfigError(Exception):
    pass


class ServerUnavailable(Exception):
    """Network error, timeout, 5xx or 429: the bridge should decide offline."""


# ------------------------------------------------------------------------------------------------------------ helpers

def utc_now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


def iso(t: dt.datetime) -> str:
    return t.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


_INSTANT = re.compile(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?)(\.\d+)?(Z|[+-]\d{2}:?\d{2})?$")


def parse_instant(value: Any) -> dt.datetime | None:
    """Parses a Java Instant as Jackson writes it ("2026-10-13T10:00:00.123456789Z") or epoch seconds."""
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return dt.datetime.fromtimestamp(float(value), dt.timezone.utc)
    m = _INSTANT.match(str(value).strip())
    if not m:
        return None
    base, frac, tz = m.group(1), m.group(2) or "", m.group(3) or "Z"
    if len(base) == 16:
        base += ":00"
    frac = (frac[:7]).ljust(7, "0") if frac else ""
    tz = "+00:00" if tz == "Z" else (tz if ":" in tz else tz[:3] + ":" + tz[3:])
    return dt.datetime.fromisoformat(base + frac + tz)


def parse_local_time(value: Any) -> dt.time | None:
    """Parses a Java LocalTime ("05:30:00", "05:30" or [5, 30, 0])."""
    if value is None:
        return None
    if isinstance(value, (list, tuple)):
        parts = [int(p) for p in value] + [0, 0]
        return dt.time(parts[0], parts[1], parts[2] if len(value) > 2 else 0)
    parts = str(value).split(":")
    sec = float(parts[2]) if len(parts) > 2 else 0.0
    return dt.time(int(parts[0]), int(parts[1]), int(sec))


def event_id(prefix: str = "br") -> str:
    """Unique per scan; the server de-duplicates on it, so a retried or re-uploaded event never counts twice."""
    return f"{prefix}-{int(time.time() * 1000):x}-{secrets.token_hex(4)}"


def mask(credential_type: str, value: str) -> str:
    """Short, non-identifying form for logs (never the full card number / QR token)."""
    v = (value or "").strip()
    if credential_type == "QR":
        return "QR pass"
    tail = v[-4:] if len(v) > 4 else v
    return ("card ..." if credential_type == "RFID_CARD" else "template ...") + tail


def resolve_zone(name: str | None) -> dt.tzinfo:
    if name and ZoneInfo is not None:
        try:
            return ZoneInfo(name)
        except (ZoneInfoNotFoundError, ValueError):
            log.warning("time zone %s not found (on Windows run: pip install tzdata); using this computer's zone", name)
    return dt.datetime.now().astimezone().tzinfo or dt.timezone.utc


# ------------------------------------------------------------------------------------------------------------ signing

def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def encode_body(body: Any) -> bytes:
    """Compact JSON exactly like gate_simulator.py; the server signs/verifies these exact bytes."""
    return b"" if body is None else json.dumps(body, separators=(",", ":")).encode()


def canonical(method: str, path: str, timestamp: str, nonce: str, raw: bytes) -> str:
    return f"{method.upper()}|{path}|{timestamp}|{nonce}|{sha256_hex(raw)}"


def sign_request(secret: str, method: str, path: str, timestamp: str, nonce: str, raw: bytes) -> str:
    return hmac.new(secret.encode(), canonical(method, path, timestamp, nonce, raw).encode(), hashlib.sha256).hexdigest()


def sign_response(secret: str, nonce: str, raw: bytes) -> str:
    return hmac.new(secret.encode(), f"response|{nonce}|{sha256_hex(raw)}".encode(), hashlib.sha256).hexdigest()


def verify_response(secret: str, nonce: str, raw: bytes, signature: str | None) -> bool:
    return bool(signature) and hmac.compare_digest(sign_response(secret, nonce, raw), (signature or "").lower())


def signed_headers(device_id: str, secret: str, method: str, path: str, raw: bytes,
                   timestamp: int | None = None, nonce: str | None = None) -> dict[str, str]:
    ts = str(int(time.time()) if timestamp is None else timestamp)
    nonce = nonce or secrets.token_hex(16)
    return {"X-Device-Id": device_id, "X-Timestamp": ts, "X-Nonce": nonce,
            "X-Signature": sign_request(secret, method, path, ts, nonce, raw)}


# -------------------------------------------------------------------------------------------------------- credentials

_CARD_UID = re.compile(r"[0-9A-Z]{4,32}")
_TEMPLATE_REF = re.compile(r"[A-Za-z0-9._:-]{1,80}")


def normalize_card_uid(raw: str | None) -> str | None:
    """Same as CredentialHashes.normalizeCardUid: strip whitespace, ':' and '-', upper-case."""
    if raw is None:
        return None
    uid = re.sub(r"[\s:\-]", "", raw).upper()
    return uid if _CARD_UID.fullmatch(uid) else None


def normalize_template_ref(raw: str | None) -> str | None:
    if raw is None:
        return None
    ref = raw.strip()
    return ref if _TEMPLATE_REF.fullmatch(ref) else None


def credential_hash(credential_type: str, value: str) -> str | None:
    """SHA-256 used by the offline allowlist (AccessDtos.AllowlistEntry); None when not matchable offline."""
    if credential_type == "RFID_CARD":
        uid = normalize_card_uid(value)
        return None if uid is None else sha256_hex(f"RFID_CARD:{uid}".encode())
    if credential_type in ("FINGERPRINT", "FACE"):
        ref = normalize_template_ref(value)
        return None if ref is None else sha256_hex(f"TEMPLATE:{credential_type}:{ref}".encode())
    return None


def decimal_card_to_hex(digits: str, reverse_bytes: bool = False) -> str:
    """Readers in "decimal" mode type the UID as a number (e.g. 0003456789); GymOps stores hex."""
    n = int(digits)
    width = max(8, (n.bit_length() + 7) // 8 * 2)
    hexed = format(n, "X").zfill(width)
    if reverse_bytes:
        hexed = "".join(reversed([hexed[i:i + 2] for i in range(0, len(hexed), 2)]))
    return hexed


# ------------------------------------------------------------------------------------------------------------- config

def deep_merge(base: dict, override: dict) -> dict:
    out = copy.deepcopy(base)
    for k, v in (override or {}).items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = deep_merge(out[k], v)
        else:
            out[k] = v
    return out


def load_config(path: str | None, overrides: dict | None = None, environ: dict | None = None) -> dict:
    environ = os.environ if environ is None else environ
    cfg = copy.deepcopy(DEFAULT_CONFIG)
    if path:
        try:
            with open(path, encoding="utf-8") as f:
                cfg = deep_merge(cfg, {k: v for k, v in json.load(f).items() if not k.startswith("_")})
        except (OSError, json.JSONDecodeError) as e:
            raise ConfigError(f"cannot read config {path}: {e}") from e
    cfg = deep_merge(cfg, {k: v for k, v in (overrides or {}).items() if v is not None})
    secret = cfg.pop("device_secret", None)
    if cfg.get("device_secret_file"):
        try:
            secret = Path(cfg["device_secret_file"]).read_text(encoding="utf-8").strip()
        except OSError as e:
            raise ConfigError(f"cannot read device_secret_file: {e}") from e
    secret = environ.get(cfg.get("device_secret_env") or "GATE_DEVICE_SECRET") or secret
    if not cfg.get("device_id"):
        raise ConfigError("device_id is missing (Access control -> Devices shows it, e.g. gd_...)")
    if not secret:
        raise ConfigError(f"device secret is missing: set the environment variable {cfg.get('device_secret_env')}")
    cfg["_secret"] = secret
    http_cfg = cfg.get("http") or {}
    cfg["_http_token"] = environ.get(http_cfg.get("token_env") or "GATE_BRIDGE_HTTP_TOKEN") or None
    validate_config(cfg)
    return cfg


def validate_config(cfg: dict) -> None:
    parsed = urllib.parse.urlparse(cfg["server_url"])
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        raise ConfigError("server_url must look like https://gym.example.com")
    if parsed.scheme == "http" and parsed.hostname not in ("localhost", "127.0.0.1", "::1"):
        log.warning("server_url uses plain http: use https:// for a published site")
    for key in ("direction",):
        if cfg.get(key) not in (None, *DIRECTIONS):
            raise ConfigError(f"{key} must be ENTRY, EXIT or null")
    if cfg["keyboard"].get("direction") not in (None, *DIRECTIONS):
        raise ConfigError("keyboard.direction must be ENTRY, EXIT or null")
    inputs = cfg.get("inputs") or []
    unknown = [i for i in inputs if i not in ("keyboard", "http", "exit-button")]
    if unknown:
        raise ConfigError(f"unknown inputs {unknown}; use keyboard, http and/or exit-button")
    if "keyboard" in inputs and "exit-button" in inputs:
        raise ConfigError("keyboard and exit-button both read stdin: use one (the keyboard input understands "
                          "the exit keyword too)")
    if cfg["keyboard"].get("card_format") not in ("hex", "decimal"):
        raise ConfigError("keyboard.card_format must be hex or decimal")
    for name in ("relay", "deny_signal"):
        kind = (cfg.get(name) or {}).get("type", "none")
        if kind not in ("print", "command", "http", "none"):
            raise ConfigError(f"{name}.type must be print, command, http or none")
        spec = cfg.get(name) or {}
        if kind == "command" and not (isinstance(spec.get("command"), list) and spec["command"]):
            raise ConfigError(f"{name}.command must be a list of arguments, e.g. [\"usbrelay\", \"RELAY_1=1\"]")
        if kind == "http" and not spec.get("url"):
            raise ConfigError(f"{name}.url is required for type http")
    if "http" in inputs and cfg["http"].get("bind") not in ("127.0.0.1", "localhost", "::1") and not cfg["_http_token"]:
        log.warning("the local HTTP input listens on %s without a token: set %s so only your terminal can call it",
                    cfg["http"].get("bind"), cfg["http"].get("token_env"))


# ------------------------------------------------------------------------------------------------------------- client

@dataclass
class Response:
    status: int
    body: Any
    raw: bytes
    verified: bool

    @property
    def ok(self) -> bool:
        return 200 <= self.status < 300 and self.verified

    @property
    def error_code(self) -> str | None:
        return self.body.get("code") if isinstance(self.body, dict) else None

    @property
    def feature_disabled(self) -> bool:
        """403 FEATURE_DISABLED: the gym switched "Gate access control" off in Settings."""
        return self.status == 403 and self.error_code == "FEATURE_DISABLED"

    def error_message(self) -> str:
        if isinstance(self.body, dict):
            return str(self.body.get("message") or self.body.get("error") or self.body)
        return str(self.body)[:200]


class GateClient:
    """Signs every request like gate firmware and verifies the server's response signature."""

    def __init__(self, server_url: str, device_id: str, secret: str, timeout: float = 4.0,
                 opener: Callable[..., Any] | None = None) -> None:
        parsed = urllib.parse.urlparse(server_url.rstrip("/"))
        self.base = server_url.rstrip("/")
        self.path_prefix = parsed.path.rstrip("/")      # signed path = what the server sees as the request URI
        self.device_id = device_id
        self.secret = secret
        self.timeout = timeout
        self.opener = opener or urllib.request.urlopen

    def call(self, method: str, path: str, body: Any = None, timeout: float | None = None) -> Response:
        raw = encode_body(body)
        full_path = self.path_prefix + path
        headers = {"Accept": "application/json", "User-Agent": "gymops-gate-bridge/" + VERSION,
                   **signed_headers(self.device_id, self.secret, method, full_path, raw)}
        nonce = headers["X-Nonce"]
        if body is not None:
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(self.base + path, data=raw if body is not None else None, method=method,
                                     headers=headers)
        try:
            with self.opener(req, timeout=timeout or self.timeout) as res:
                payload = res.read()
                verified = verify_response(self.secret, nonce, payload, res.headers.get("X-Signature"))
                return Response(res.status, _json(payload), payload, verified)
        except urllib.error.HTTPError as e:
            payload = e.read() if hasattr(e, "read") else b""
            if e.code >= 500 or e.code == 429:
                raise ServerUnavailable(f"HTTP {e.code}") from e
            return Response(e.code, _json(payload), payload, False)
        except (urllib.error.URLError, socket.timeout, TimeoutError, ConnectionError, OSError) as e:
            raise ServerUnavailable(str(getattr(e, "reason", e))) from e


def _json(payload: bytes) -> Any:
    try:
        return json.loads(payload or b"null")
    except (json.JSONDecodeError, UnicodeDecodeError):
        return payload.decode(errors="replace")


def with_retries(fn: Callable[[], Response], attempts: int, base_delay: float = 0.5, max_delay: float = 10.0,
                 stop: threading.Event | None = None) -> Response:
    """Retries ServerUnavailable with exponential backoff (each attempt is freshly signed: new nonce/timestamp)."""
    delay = base_delay
    for attempt in range(max(1, attempts)):
        try:
            return fn()
        except ServerUnavailable:
            if attempt == attempts - 1 or (stop is not None and stop.is_set()):
                raise
            if stop is not None:
                stop.wait(delay)
            else:
                time.sleep(delay)
            delay = min(delay * 2, max_delay)
    raise ServerUnavailable("no attempts")  # pragma: no cover


# ---------------------------------------------------------------------------------------------- offline allowlist

@dataclass
class OfflineDecision:
    granted: bool
    reason: str
    message: str
    member_ref: str | None = None
    display_name: str | None = None


class Allowlist:
    """The signed offline allowlist (AccessDtos.Allowlist) with an index by (credentialType, credentialHash)."""

    def __init__(self, data: dict) -> None:
        self.data = data
        self.zone = resolve_zone(data.get("timezone"))
        self.valid_until = parse_instant(data.get("validUntil"))
        self.generated_at = parse_instant(data.get("generatedAt"))
        self.exit_mode = data.get("exitMode")
        self.hours = {int(h["dayOfWeek"]): h for h in data.get("openingHours") or []}
        self.index = {(e.get("credentialType"), (e.get("credentialHash") or "").lower()): e
                      for e in data.get("entries") or []}

    def __len__(self) -> int:
        return len(self.index)

    def branch_open(self, local: dt.datetime) -> bool:
        """Same rule as GymBranch.isOpenAt: no schedule = always open; a missing day = closed."""
        if not self.hours:
            return True
        h = self.hours.get(local.isoweekday())   # 1 = Monday ... 7 = Sunday, like java.time.DayOfWeek
        if h is None or h.get("closed"):
            return False
        start, end = parse_local_time(h.get("openTime")), parse_local_time(h.get("closeTime"))
        if start is None or end is None:
            return False
        return start <= local.time() < end

    def decide(self, credential_type: str, value: str, direction: str, now: dt.datetime,
               allow_unknown_exit: bool = True, entries_today: Callable[[str], int] | None = None) -> OfflineDecision:
        if credential_type == "QR":
            return OfflineDecision(False, "OFFLINE_DENIED", "QR passes need the server; use your card or fingerprint")
        digest = credential_hash(credential_type, value)
        entry = self.index.get((credential_type, digest)) if digest else None
        name = entry.get("displayName") if entry else None
        ref = entry.get("memberRef") if entry else None
        if direction == "EXIT":
            if entry is not None or allow_unknown_exit:
                return OfflineDecision(True, "OK", "Goodbye (offline)", ref, name)
            return OfflineDecision(False, "UNKNOWN_CREDENTIAL", "Credential not recognised (offline)")
        if entry is None:
            return OfflineDecision(False, "UNKNOWN_CREDENTIAL", "Credential not recognised (offline)")
        if self.valid_until is not None and now >= self.valid_until:
            return OfflineDecision(False, "OFFLINE_DENIED", "Offline allowlist has expired", ref, name)
        valid_from, valid_until = parse_instant(entry.get("validFrom")), parse_instant(entry.get("validUntil"))
        if (valid_from is not None and now < valid_from - SKEW_TOLERANCE) or (valid_until is not None and now >= valid_until):
            return OfflineDecision(False, "NO_ACTIVE_MEMBERSHIP", "Membership not valid at this time (offline)", ref, name)
        local = now.astimezone(self.zone)
        if not self.branch_open(local):
            return OfflineDecision(False, "BRANCH_CLOSED", "The branch is closed now (offline)", ref, name)
        start, end = parse_local_time(entry.get("dailyAccessStart")), parse_local_time(entry.get("dailyAccessEnd"))
        if start is not None and end is not None and not (start <= local.time() < end):
            return OfflineDecision(False, "OUTSIDE_PLAN_HOURS",
                                   f"Plan allows entry between {start:%H:%M} and {end:%H:%M} (offline)", ref, name)
        limit = entry.get("maxEntriesPerDay")   # only if a future server adds it to the allowlist
        if isinstance(limit, int) and limit > 0 and entries_today is not None and ref and entries_today(ref) >= limit:
            return OfflineDecision(False, "DAILY_ENTRY_LIMIT", "Daily entry limit reached (offline)", ref, name)
        return OfflineDecision(True, "OK", "Welcome (offline)", ref, name)


class AllowlistStore:
    """Caches the allowlist on disk together with the request nonce and response signature, and re-verifies the
    signature on every load, so a file edited by someone without the device secret is ignored."""

    def __init__(self, path: Path, secret: str) -> None:
        self.path = path
        self.secret = secret
        self.lock = threading.Lock()
        self.current: Allowlist | None = None

    def load(self) -> Allowlist | None:
        try:
            stored = json.loads(self.path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            return None
        except (OSError, json.JSONDecodeError) as e:
            log.warning("cached allowlist unreadable (%s); ignoring it", e)
            return None
        raw = stored.get("body", "").encode()
        if not verify_response(self.secret, stored.get("nonce", ""), raw, stored.get("signature")):
            log.error("cached allowlist signature does not verify (tampered, or the device secret was rotated); ignoring it")
            return None
        with self.lock:
            self.current = Allowlist(json.loads(raw))
        return self.current

    def save(self, nonce: str, signature: str, raw: bytes) -> Allowlist:
        allowlist = Allowlist(json.loads(raw))
        atomic_write(self.path, json.dumps({"savedAt": iso(utc_now()), "nonce": nonce, "signature": signature,
                                            "body": raw.decode()}))
        with self.lock:
            self.current = allowlist
        return allowlist

    def get(self) -> Allowlist | None:
        with self.lock:
            return self.current


def atomic_write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(text)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


# ----------------------------------------------------------------------------------------------------- event buffer

class EventBuffer:
    """Append-only JSONL of events decided while offline: {"kind": "scan"|"exit_button"|"alarm", "event": {...}}.
    Lines are removed only after the server confirmed them (uploads are idempotent per deviceEventId)."""

    def __init__(self, path: Path, rejected_path: Path) -> None:
        self.path = path
        self.rejected_path = rejected_path
        self.lock = threading.Lock()

    def append(self, kind: str, event: dict) -> None:
        line = json.dumps({"kind": kind, "event": event}, separators=(",", ":")) + "\n"
        with self.lock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
            with os.fdopen(fd, "a", encoding="utf-8") as f:
                f.write(line)
                f.flush()
                os.fsync(f.fileno())

    def read(self) -> list[dict]:
        with self.lock:
            return self._read()

    def _read(self) -> list[dict]:
        try:
            lines = self.path.read_text(encoding="utf-8").splitlines()
        except FileNotFoundError:
            return []
        out = []
        for line in lines:
            try:
                rec = json.loads(line)
                if isinstance(rec, dict) and "event" in rec:
                    out.append(rec)
            except json.JSONDecodeError:
                log.warning("skipping a corrupt line in %s", self.path.name)
        return out

    def depth(self) -> int:
        return len(self.read())

    def remove(self, event_ids: set[str], rejected: list[dict] | None = None) -> None:
        with self.lock:
            keep = [r for r in self._read() if r["event"].get("deviceEventId") not in event_ids]
            atomic_write(self.path, "".join(json.dumps(r, separators=(",", ":")) + "\n" for r in keep))
            if rejected:
                fd = os.open(self.rejected_path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
                with os.fdopen(fd, "a", encoding="utf-8") as f:
                    for r in rejected:
                        f.write(json.dumps(r, separators=(",", ":")) + "\n")


# -------------------------------------------------------------------------------------------------------------- relay

def _fill(template: Any, values: dict[str, Any], quote: bool = False) -> Any:
    """Replaces {seconds}, {decision}, {reason}, {direction} in strings (recursively in lists / dicts)."""
    if isinstance(template, str):
        out = template
        for k, v in values.items():
            s = "" if v is None else str(v)
            out = out.replace("{" + k + "}", urllib.parse.quote(s, safe="") if quote else s)
        return out
    if isinstance(template, list):
        return [_fill(t, values, quote) for t in template]
    if isinstance(template, dict):
        return {k: _fill(v, values, quote) for k, v in template.items()}
    return template


class Actuator:
    """Runs one output spec: print | command (argument list, never a shell) | http | none."""

    def __init__(self, name: str, spec: dict, dry_run: bool, stop: threading.Event) -> None:
        self.name = name
        self.spec = spec or {"type": "none"}
        self.kind = self.spec.get("type", "none")
        self.dry_run = dry_run
        self.stop = stop
        self.jobs: queue.Queue = queue.Queue()
        self.worker = threading.Thread(target=self._run, name=f"{name}-worker", daemon=True)
        self.worker.start()

    def trigger(self, seconds: int, decision: str, reason: str, direction: str | None) -> None:
        values = {"seconds": seconds, "decision": decision, "reason": reason, "direction": direction or ""}
        if self.kind == "none":
            return
        if self.kind == "print" or self.dry_run:
            prefix = "[dry-run] " if self.dry_run and self.kind != "print" else ""
            if self.name == "relay":
                log.info("%sRELAY: OPEN for %ss (%s %s)", prefix, seconds, decision, reason)
            else:
                log.info("%sDENY SIGNAL: %s (%s)", prefix, decision, reason)
            return
        self.jobs.put(values)

    def _run(self) -> None:
        close_key = "close_command" if self.kind == "command" else "close_url"
        while True:
            values = self.jobs.get()
            if values is None:
                return
            self._safe("open", values)
            if not self.spec.get(close_key):
                continue            # the relay times itself (e.g. {seconds} passed to the command / URL)
            # keep the gate open for the requested time; a new GRANTED meanwhile extends it instead of queueing,
            # and the close action always runs (also on shutdown): never leave the door unlocked
            deadline = time.monotonic() + float(values["seconds"])
            finished = False
            while not self.stop.is_set():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                try:
                    nxt = self.jobs.get(timeout=min(remaining, 0.5))
                except queue.Empty:
                    continue
                if nxt is None:
                    finished = True
                    break
                deadline = max(deadline, time.monotonic() + float(nxt["seconds"]))
            self._safe("close", values)
            if finished:
                return

    def _safe(self, phase: str, values: dict) -> None:
        try:
            self._do(phase, values)
        except Exception as e:  # noqa: BLE001 - an output failure must never kill the bridge
            log.error("%s %s action failed: %s", self.name, phase, e)

    def _do(self, phase: str, values: dict) -> None:
        if self.kind == "command":
            argv = _fill(self.spec["command"] if phase == "open" else self.spec["close_command"], values)
            result = subprocess.run([str(a) for a in argv], capture_output=True, timeout=15, check=False)
            if result.returncode != 0:
                log.error("%s %s command exited %s: %s", self.name, phase, result.returncode,
                          result.stderr.decode(errors="replace").strip()[:200])
            else:
                log.info("%s %s: command ran", self.name.upper(), phase)
        elif self.kind == "http":
            url = _fill(self.spec["url"] if phase == "open" else self.spec["close_url"], values, quote=True)
            method = (self.spec.get("method") or ("POST" if self.spec.get("body") is not None else "GET")).upper()
            body = self.spec.get("body")
            data = None if body is None else json.dumps(_fill(body, values)).encode()
            headers = {"Content-Type": "application/json", **(self.spec.get("headers") or {})}
            req = urllib.request.Request(url, data=data, method=method, headers=headers)
            with urllib.request.urlopen(req, timeout=float(self.spec.get("timeout_seconds", 3))) as res:
                log.info("%s %s: HTTP %s", self.name.upper(), phase, res.status)

    def close(self, timeout: float = 5.0) -> None:
        self.jobs.put(None)
        self.worker.join(timeout)


# -------------------------------------------------------------------------------------------------- keyboard parsing

@dataclass
class KeyboardAction:
    kind: str                      # scan | exit_button | ack | fail | status | ignore
    credential_type: str | None = None
    value: str | None = None
    command_id: int | None = None
    template_ref: str | None = None
    message: str | None = None


def parse_keyboard_line(line: str, kb: dict) -> KeyboardAction:
    """Turns one line typed by a USB reader (or an operator) into an action."""
    text = line.strip().strip("\x02\x03")          # some readers wrap the data in STX / ETX
    if not text:
        return KeyboardAction("ignore", message="empty line")
    exit_kw = (kb.get("exit_keyword") or "EXIT").strip()
    if text.startswith("!"):
        parts = text[1:].split()
        cmd = parts[0].lower() if parts else ""
        if cmd in ("exit", "exit-button", "exitbutton"):
            return KeyboardAction("exit_button")
        if cmd == "status":
            return KeyboardAction("status")
        if cmd in ("ack", "fail") and len(parts) >= 2 and parts[1].isdigit():
            if cmd == "ack":
                return KeyboardAction("ack", command_id=int(parts[1]),
                                      template_ref=parts[2] if len(parts) > 2 else None)
            return KeyboardAction("fail", command_id=int(parts[1]), message=" ".join(parts[2:]) or "failed at the gate")
        return KeyboardAction("ignore", message=f"unknown command {text!r} (try !exit, !status, !ack <id> <ref>)")
    if exit_kw and text.upper() == exit_kw.upper():
        return KeyboardAction("exit_button")
    qr = kb.get("qr_prefix") or "GQ1."
    if text.startswith(qr):
        return KeyboardAction("scan", "QR", text)
    for key, ctype in (("fingerprint_prefix", "FINGERPRINT"), ("face_prefix", "FACE")):
        prefix = kb.get(key)
        if prefix and text.upper().startswith(prefix.upper()):
            ref = normalize_template_ref(text[len(prefix):])
            if ref is None:
                return KeyboardAction("ignore", message="invalid template reference")
            return KeyboardAction("scan", ctype, ref)
    if kb.get("card_format") == "decimal":
        if not text.isdigit():
            return KeyboardAction("ignore", message="expected a decimal card number")
        text = decimal_card_to_hex(text, bool(kb.get("reverse_card_bytes")))
    uid = normalize_card_uid(text)
    if uid is None or not re.fullmatch(r"[0-9A-F]+", uid):     # card UIDs are hexadecimal (as enrolled in GymOps)
        return KeyboardAction("ignore", message="not a card number, QR pass or command")
    if kb.get("reverse_card_bytes") and kb.get("card_format") != "decimal" and len(uid) % 2 == 0:
        uid = "".join(reversed([uid[i:i + 2] for i in range(0, len(uid), 2)]))
    return KeyboardAction("scan", "RFID_CARD", uid)


# -------------------------------------------------------------------------------------------------------------- bridge

@dataclass
class State:
    online: bool | None = None
    last_server_contact: dt.datetime | None = None
    clock_offset_seconds: float | None = None
    exit_mode: str | None = None
    pending_commands: dict[int, dict] = field(default_factory=dict)
    forwarded_commands: set[int] = field(default_factory=set)
    offline_entries: dict[tuple[str, str], int] = field(default_factory=dict)
    exit_button_supported: bool | None = None
    gates_disabled: bool = False


class Bridge:
    def __init__(self, cfg: dict, dry_run: bool = False, client: GateClient | None = None) -> None:
        self.cfg = cfg
        self.dry_run = dry_run
        self.secret = cfg["_secret"]
        self.stop = threading.Event()
        self.state = State()
        self.state_lock = threading.Lock()
        self.started = time.monotonic()
        state_dir = Path(cfg["state_dir"]).expanduser()
        state_dir.mkdir(parents=True, exist_ok=True)
        self.client = client or GateClient(cfg["server_url"], cfg["device_id"], self.secret,
                                           float(cfg["request_timeout_seconds"]))
        self.allowlist = AllowlistStore(state_dir / "allowlist.json", self.secret)
        self.buffer = EventBuffer(state_dir / "offline-events.jsonl", state_dir / "rejected-events.jsonl")
        self.relay = Actuator("relay", cfg.get("relay"), dry_run, self.stop)
        self.deny = Actuator("deny_signal", cfg.get("deny_signal"), dry_run, self.stop)
        self.wake_commands = threading.Event()
        self.wake_upload = threading.Event()
        self.wake_allowlist = threading.Event()
        self.allowlist_interval = float(cfg["allowlist_refresh_seconds"])
        self.http_server: http.server.ThreadingHTTPServer | None = None

    # ---------------------------------------------------------------------------------------------- connectivity

    def _mark(self, online: bool, why: str = "") -> None:
        with self.state_lock:
            was = self.state.online
            self.state.online = online
            if online:
                self.state.last_server_contact = utc_now()
        if online and self.state.gates_disabled:
            self._gates_disabled(False)
        if was is not online:
            if online:
                log.info("server reachable: ONLINE")
                self.wake_upload.set()
            else:
                log.warning("server unreachable (%s): OFFLINE mode, deciding from the cached allowlist", why)

    def _note_exit_mode(self, body: Any) -> None:
        mode = body.get("exitMode") if isinstance(body, dict) else None
        if mode and mode != self.state.exit_mode:
            self.state.exit_mode = mode
            log.info("branch exit mode: %s (%s)", mode, "push-button / free exit" if mode == "BUTTON" else "scan to exit")

    def _gates_disabled(self, disabled: bool) -> None:
        if disabled and not self.state.gates_disabled:
            log.error("GATES ARE SWITCHED OFF for this gym (403 FEATURE_DISABLED: Settings -> Gate access control). "
                      "Scans are refused; the bridge keeps running and checks again every few minutes.")
        elif not disabled and self.state.gates_disabled:
            log.info("gate access control is switched on again")
        self.state.gates_disabled = disabled

    def _auth_problem(self, resp: Response) -> None:
        msg = resp.error_message()
        if resp.feature_disabled:
            self._gates_disabled(True)
            return
        if resp.status == 401 and "skew" in msg:
            m = re.search(r"server time (\d+)", msg)
            off = f" (this computer is {int(time.time()) - int(m.group(1)):+d}s off)" if m else ""
            log.error("clock problem: the server rejected our timestamp%s. Turn on automatic time (NTP).", off)
        elif resp.status == 401:
            log.error("server rejected the device credentials (HTTP 401): check device_id and the device secret "
                      "(rotating the secret in the web app invalidates the old one)")
        elif resp.status == 403:
            log.error("device is disabled in GymOps (HTTP 403): %s", msg)
        else:
            log.error("server answered HTTP %s: %s", resp.status, msg)

    # ----------------------------------------------------------------------------------------------------- scans

    def handle_scan(self, credential_type: str, value: str, direction: str | None = None,
                    source: str = "keyboard", drive_relay: bool = True) -> dict:
        direction = direction or self.cfg.get("direction")
        eid = event_id()
        body: dict[str, Any] = {"deviceEventId": eid, "credentialType": credential_type, "credentialValue": value}
        if direction:
            body["direction"] = direction
        shown = mask(credential_type, value)
        attempts, timeout = self._scan_budget()
        try:
            resp = with_retries(lambda: self.client.call("POST", GATE_PREFIX + "/events", body, timeout=timeout),
                                attempts, base_delay=0.3, max_delay=1.0, stop=self.stop)
            if resp.ok and isinstance(resp.body, dict):
                self._mark(True)
                return self._act(resp.body, "server", shown, drive_relay)
            if 200 <= resp.status < 300:
                log.error("response signature did NOT verify: ignoring the answer and deciding offline")
                raise ServerUnavailable("unverified response")
            self._auth_problem(resp)
            if resp.status in (400, 403, 404, 422):
                reason = "FEATURE_DISABLED" if resp.feature_disabled else "OFFLINE_DENIED"
                return self._act({"deviceEventId": eid, "decision": "DENIED", "reasonCode": reason,
                                  "message": resp.error_message(), "openGate": False, "openSeconds": 0,
                                  "direction": direction}, "error", shown, drive_relay)
            raise ServerUnavailable(f"HTTP {resp.status}")
        except ServerUnavailable as e:
            self._mark(False, str(e))
            return self._offline_scan(eid, credential_type, value, direction, shown, drive_relay)

    def _scan_budget(self) -> tuple[int, float]:
        """While known to be offline, try the server once with a short timeout so members are not kept waiting;
        the heartbeat loop notices when the connection is back."""
        timeout = float(self.cfg["request_timeout_seconds"])
        if self.state.online is False:
            return 1, min(timeout, 1.5)
        return int(self.cfg["scan_retries"]) + 1, timeout

    def _offline_scan(self, eid: str, credential_type: str, value: str, direction: str | None, shown: str,
                      drive_relay: bool) -> dict:
        now = utc_now()
        effective = direction or "ENTRY"
        allowlist = self.allowlist.get()
        off = self.cfg["offline"]
        if not off.get("enabled", True):
            decision = OfflineDecision(False, "OFFLINE_DENIED", "Offline mode is switched off")
        elif allowlist is None:
            decision = (OfflineDecision(True, "OK", "Goodbye (offline, no allowlist)")
                        if effective == "EXIT" and off.get("allow_unknown_exit", True)
                        else OfflineDecision(False, "OFFLINE_DENIED", "No offline allowlist downloaded yet"))
        else:
            local_day = now.astimezone(allowlist.zone).date().isoformat()
            decision = allowlist.decide(credential_type, value, effective, now, bool(off.get("allow_unknown_exit", True)),
                                        lambda ref: self.state.offline_entries.get((ref, local_day), 0))
            if decision.granted and effective == "ENTRY" and decision.member_ref:
                key = (decision.member_ref, local_day)
                self.state.offline_entries[key] = self.state.offline_entries.get(key, 0) + 1
        event: dict[str, Any] = {"deviceEventId": eid, "credentialType": credential_type, "credentialValue": value,
                                 "direction": effective, "occurredAt": iso(now),
                                 "offlineDecision": "GRANTED" if decision.granted else "DENIED"}
        if not decision.granted:
            event["offlineReason"] = decision.reason
        self.buffer.append("scan", event)
        seconds = int(off.get("open_seconds", self.cfg["default_open_seconds"]))
        return self._act({"deviceEventId": eid, "decision": event["offlineDecision"], "reasonCode": decision.reason,
                          "message": decision.message, "openGate": decision.granted,
                          "openSeconds": seconds if decision.granted else 0, "memberDisplayName": decision.display_name,
                          "direction": effective, "offline": True}, "offline", shown, drive_relay)

    def _act(self, d: dict, source: str, shown: str, drive_relay: bool) -> dict:
        decision, reason = d.get("decision"), d.get("reasonCode")
        opened = bool(d.get("openGate"))
        seconds = int(d.get("openSeconds") or self.cfg["default_open_seconds"]) if opened else 0
        log.info("%s %s %s %s %s [%s]%s", decision, reason, d.get("direction") or "", shown,
                 d.get("memberDisplayName") or "", source, f" open {seconds}s" if opened else "")
        if d.get("replayed"):
            log.info("(server returned the stored decision for this event id: nothing new was recorded)")
        if drive_relay:
            if opened:
                self.relay.trigger(seconds, decision or "", reason or "", d.get("direction"))
            elif decision == "DENIED":
                self.deny.trigger(0, decision, reason or "", d.get("direction"))
        out = {k: d.get(k) for k in ("deviceEventId", "decision", "reasonCode", "message", "openGate", "openSeconds",
                                     "memberDisplayName", "direction", "antiPassbackViolation", "replayed")}
        out.update({"openSeconds": seconds, "source": source, "relayDriven": drive_relay and opened})
        return out

    # ----------------------------------------------------------------------------------------------- exit button

    def handle_exit_button(self, source: str = "keyboard", drive_relay: bool = True) -> dict:
        eid = event_id("exit")
        off = self.cfg["offline"]
        seconds = int(off.get("open_seconds", self.cfg["default_open_seconds"]))
        local = {"deviceEventId": eid, "decision": "GRANTED", "reasonCode": "OK", "message": "Exit button",
                 "openGate": bool(off.get("exit_button_opens", True)), "openSeconds": seconds, "direction": "EXIT"}
        try:
            attempts, timeout = self._scan_budget()
            resp = with_retries(lambda: self.client.call("POST", GATE_PREFIX + "/exit-button", {"deviceEventId": eid},
                                                         timeout=timeout),
                                attempts, base_delay=0.3, max_delay=1.0, stop=self.stop)
            if resp.ok and isinstance(resp.body, dict):
                self._mark(True)
                self.state.exit_button_supported = True
                return self._act(resp.body, "server", "exit button", drive_relay)
            if resp.status == 404:
                if self.state.exit_button_supported is not False:
                    log.warning("this GymOps server has no /exit-button endpoint yet: opening locally, not logged")
                self.state.exit_button_supported = False
                return self._act(local, "local", "exit button", drive_relay)
            self._auth_problem(resp)
            return self._act(local, "local", "exit button", drive_relay)
        except ServerUnavailable as e:
            self._mark(False, str(e))
            self.buffer.append("exit_button", {"deviceEventId": eid, "occurredAt": iso(utc_now())})
            return self._act({**local, "offline": True}, "offline", "exit button", drive_relay)

    # ------------------------------------------------------------------------------------------- alarms, commands

    def handle_alarm(self, alarm_type: str, details: str | None = None) -> dict:
        if alarm_type not in ALARM_TYPES:
            return {"error": f"type must be one of {', '.join(ALARM_TYPES)}"}
        event = {"deviceEventId": event_id("alarm"), "type": alarm_type, "occurredAt": iso(utc_now())}
        if details:
            event["details"] = str(details)[:300]
        try:
            resp = with_retries(lambda: self.client.call("POST", GATE_PREFIX + "/alarms", event), 3, stop=self.stop)
            if resp.ok:
                log.warning("ALARM %s reported", alarm_type)
                return {"reported": True, **(resp.body if isinstance(resp.body, dict) else {})}
            self._auth_problem(resp)
            return {"reported": False, "status": resp.status, "message": resp.error_message()}
        except ServerUnavailable as e:
            self._mark(False, str(e))
            self.buffer.append("alarm", event)
            log.warning("ALARM %s buffered (offline)", alarm_type)
            return {"reported": False, "buffered": True}

    def ack_command(self, command_id: int, success: bool, template_ref: str | None, message: str | None) -> dict:
        body = {"success": bool(success), "templateRef": template_ref,
                "message": (message or ("completed at the gate" if success else "failed at the gate"))[:300]}
        try:
            resp = with_retries(lambda: self.client.call("POST", f"{GATE_PREFIX}/commands/{int(command_id)}/ack", body),
                                3, stop=self.stop)
        except ServerUnavailable as e:
            self._mark(False, str(e))
            return {"acknowledged": False, "message": "server unreachable; try again"}
        if resp.ok:
            self.state.pending_commands.pop(int(command_id), None)
            log.info("command %s acknowledged (%s)", command_id, "success" if success else "failure")
            return {"acknowledged": True, "command": resp.body}
        self._auth_problem(resp)
        return {"acknowledged": False, "status": resp.status, "message": resp.error_message()}

    def poll_commands(self) -> None:
        resp = self.client.call("GET", GATE_PREFIX + "/commands")
        if not resp.ok:
            self._auth_problem(resp)
            return
        self._mark(True)
        commands = (resp.body or {}).get("commands") or []
        self.state.pending_commands = {int(c["id"]): c for c in commands}
        for c in commands:
            cid = int(c["id"])
            if cid in self.state.forwarded_commands:
                continue
            self.state.forwarded_commands.add(cid)
            if c.get("type") == "ENROLL_TEMPLATE":
                log.info("COMMAND #%s ENROLL_TEMPLATE %s for member %s: capture on the device, then acknowledge with "
                         "the device's template reference (POST /commands/%s/ack {\"success\":true,\"templateRef\":\"...\"} "
                         "on the local HTTP input, or type: !ack %s <templateRef>)",
                         cid, c.get("credentialType"), c.get("memberRef"), cid, cid)
            else:
                log.info("COMMAND #%s %s %s template %s: erase it on the device, then acknowledge (!ack %s)",
                         cid, c.get("type"), c.get("credentialType"), c.get("templateRef"), cid)
            self._forward_command(c)

    def _forward_command(self, command: dict) -> None:
        url = (self.cfg.get("commands") or {}).get("forward_url")
        if not url:
            return
        if self.dry_run:
            log.info("[dry-run] would forward command #%s to the vendor endpoint", command.get("id"))
            return
        try:
            req = urllib.request.Request(url, data=json.dumps(command).encode(), method="POST",
                                         headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=5) as res:
                log.info("command #%s forwarded to vendor endpoint (HTTP %s)", command.get("id"), res.status)
        except (urllib.error.URLError, OSError) as e:
            log.error("could not forward command #%s: %s (will retry on restart)", command.get("id"), e)
            self.state.forwarded_commands.discard(int(command["id"]))

    # --------------------------------------------------------------------------------------- heartbeat / allowlist

    def heartbeat(self) -> Response:
        body: dict[str, Any] = {"firmwareVersion": str(self.cfg["firmware_version"])[:40],
                                "uptimeSeconds": int(time.monotonic() - self.started),
                                "offlineQueueDepth": min(self.buffer.depth(), 1_000_000)}
        resp = self.client.call("POST", GATE_PREFIX + "/heartbeat", body)
        if not resp.ok:
            if 200 <= resp.status < 300:
                log.error("heartbeat response signature did NOT verify")
            else:
                self._auth_problem(resp)
            return resp
        self._mark(True)
        data = resp.body if isinstance(resp.body, dict) else {}
        server_time = parse_instant(data.get("serverTime"))
        if server_time:
            offset = (utc_now() - server_time).total_seconds()
            self.state.clock_offset_seconds = offset
            if abs(offset) > 60:
                log.warning("this computer's clock is %+.0fs off the server: requests fail beyond 300s; enable NTP", offset)
        self._note_exit_mode(data)
        if data.get("deviceStatus") and data["deviceStatus"] != "ACTIVE":
            log.error("device status on the server: %s", data["deviceStatus"])
        if int(data.get("pendingCommands") or 0) > 0:
            self.wake_commands.set()
        refresh = data.get("allowlistRefreshSeconds")
        if isinstance(refresh, int) and refresh > 0:
            self.allowlist_interval = float(min(refresh, int(self.cfg["allowlist_refresh_seconds"])))
        return resp

    def refresh_allowlist(self) -> Allowlist | None:
        raw = encode_body(None)
        path = self.client.path_prefix + GATE_PREFIX + "/allowlist"
        headers = signed_headers(self.cfg["device_id"], self.secret, "GET", path, raw)
        # done by hand (not GateClient.call) because the nonce and signature are stored with the cached copy
        req = urllib.request.Request(self.client.base + GATE_PREFIX + "/allowlist", method="GET",
                                     headers={"Accept": "application/json", **headers})
        try:
            with self.client.opener(req, timeout=max(15.0, self.client.timeout)) as res:
                payload, signature = res.read(), res.headers.get("X-Signature")
        except urllib.error.HTTPError as e:
            if e.code >= 500 or e.code == 429:
                raise ServerUnavailable(f"HTTP {e.code}") from e
            self._auth_problem(Response(e.code, _json(e.read()), b"", False))
            return None
        except (urllib.error.URLError, socket.timeout, TimeoutError, OSError) as e:
            raise ServerUnavailable(str(getattr(e, "reason", e))) from e
        if not verify_response(self.secret, headers["X-Nonce"], payload, signature):
            log.error("allowlist signature did NOT verify: keeping the previous copy")
            return None
        allowlist = self.allowlist.save(headers["X-Nonce"], signature or "", payload)
        self._mark(True)
        self._note_exit_mode(allowlist.data)
        log.info("offline allowlist updated: %d credentials, valid until %s (%s)", len(allowlist),
                 allowlist.data.get("validUntil"), allowlist.data.get("timezone"))
        return allowlist

    # ----------------------------------------------------------------------------------------------------- upload

    def upload_buffer(self) -> int:
        """Uploads buffered offline events; returns how many the server confirmed."""
        records = self.buffer.read()
        if not records:
            return 0
        confirmed: set[str] = set()
        rejected: list[dict] = []
        scans = [r for r in records if r["kind"] == "scan"]
        for i in range(0, len(scans), MAX_BATCH):
            chunk = scans[i:i + MAX_BATCH]
            resp = self.client.call("POST", GATE_PREFIX + "/events/batch", {"events": [r["event"] for r in chunk]},
                                    timeout=30)
            if resp.ok:
                confirmed.update(r["event"]["deviceEventId"] for r in chunk)
                for d in (resp.body or {}).get("results") or []:
                    if d.get("reasonCode") not in (None, "OK"):
                        log.info("offline event %s reconciled: %s %s", d.get("deviceEventId"), d.get("decision"),
                                 d.get("reasonCode"))
            elif resp.status in (400, 422):
                # find the bad event(s) one by one so one malformed line cannot block the queue forever
                for r in chunk:
                    single = self.client.call("POST", GATE_PREFIX + "/events/batch", {"events": [r["event"]]})
                    if single.ok:
                        confirmed.add(r["event"]["deviceEventId"])
                    elif single.status in (400, 422):
                        rejected.append({**r, "error": single.error_message()})
                        confirmed.add(r["event"]["deviceEventId"])
            else:
                self._auth_problem(resp)
                break
        for r in records:
            if r["kind"] == "scan":
                continue
            path = "/exit-button" if r["kind"] == "exit_button" else "/alarms"
            resp = self.client.call("POST", GATE_PREFIX + path, r["event"])
            if resp.ok:
                confirmed.add(r["event"]["deviceEventId"])
            elif resp.status in (400, 404, 422):
                rejected.append({**r, "error": f"HTTP {resp.status} {resp.error_message()}"})
                confirmed.add(r["event"]["deviceEventId"])
        if confirmed:
            self.buffer.remove(confirmed, rejected)
            log.info("uploaded %d offline events%s", len(confirmed) - len(rejected),
                     f"; {len(rejected)} rejected (see rejected-events.jsonl)" if rejected else "")
            self._mark(True)
        return len(confirmed) - len(rejected)

    # ------------------------------------------------------------------------------------------------------ loops

    def _loop(self, name: str, interval: Callable[[], float], fn: Callable[[], Any], wake: threading.Event | None = None,
              initial_delay: float = 0.0) -> None:
        if initial_delay:
            self.stop.wait(initial_delay)
        failures = 0
        while not self.stop.is_set():
            try:
                fn()
                failures = 0
            except ServerUnavailable as e:
                failures += 1
                self._mark(False, str(e))
            except Exception:  # noqa: BLE001 - keep the gate running whatever happens in a background task
                failures += 1
                log.exception("%s failed", name)
            delay = interval() if failures == 0 else min(interval(), 5.0 * 2 ** min(failures, 6))
            if self.state.gates_disabled:
                delay = max(delay, 300.0)     # gates switched off in Settings: just check back now and then
            if wake is not None:
                wake.wait(delay)
                wake.clear()
            else:
                self.stop.wait(delay)

    def start_background(self) -> list[threading.Thread]:
        loops = [
            ("heartbeat", lambda: float(self.cfg["heartbeat_seconds"]), self.heartbeat, None, 0.0),
            ("allowlist", lambda: self.allowlist_interval, self.refresh_allowlist, self.wake_allowlist, 1.0),
            ("commands", lambda: float(self.cfg["commands_poll_seconds"]), self.poll_commands, self.wake_commands, 2.0),
            ("upload", lambda: float(self.cfg["upload_interval_seconds"]), self.upload_buffer, self.wake_upload, 3.0),
        ]
        threads = []
        for name, interval, fn, wake, delay in loops:
            t = threading.Thread(target=self._loop, args=(name, interval, fn, wake, delay), name=name, daemon=True)
            t.start()
            threads.append(t)
        return threads

    def status(self) -> dict:
        allowlist = self.allowlist.get()
        return {"deviceId": self.cfg["device_id"], "online": self.state.online,
                "lastServerContact": iso(self.state.last_server_contact) if self.state.last_server_contact else None,
                "clockOffsetSeconds": self.state.clock_offset_seconds, "exitMode": self.state.exit_mode,
                "gatesDisabled": self.state.gates_disabled, "offlineQueueDepth": self.buffer.depth(),
                "allowlist": None if allowlist is None else {"entries": len(allowlist),
                                                             "validUntil": allowlist.data.get("validUntil")},
                "pendingCommands": list(self.state.pending_commands.values()), "dryRun": self.dry_run}

    def shutdown(self) -> None:
        self.stop.set()
        for ev in (self.wake_commands, self.wake_upload, self.wake_allowlist):
            ev.set()
        if self.http_server is not None:
            self.http_server.shutdown()
            self.http_server.server_close()
        self.relay.close()
        self.deny.close()

    # ------------------------------------------------------------------------------------------------------ inputs

    def run_keyboard(self, exit_button_only: bool = False, stream=None) -> None:
        stream = stream or sys.stdin
        kb = self.cfg["keyboard"]
        direction = kb.get("direction") or self.cfg.get("direction")
        if exit_button_only:
            log.info("exit-button input: every Enter on this console is an exit-button press")
        else:
            log.info("keyboard input ready: present a card / QR code (or type !exit, !status, !ack <id> <ref>)")
        for line in stream:
            if self.stop.is_set():
                break
            if exit_button_only:
                self.handle_exit_button("exit-button")
                continue
            action = parse_keyboard_line(line, kb)
            if action.kind == "scan":
                self.handle_scan(action.credential_type or "RFID_CARD", action.value or "", direction, "keyboard")
            elif action.kind == "exit_button":
                self.handle_exit_button("keyboard")
            elif action.kind in ("ack", "fail"):
                print(json.dumps(self.ack_command(action.command_id or 0, action.kind == "ack", action.template_ref,
                                                  action.message), indent=2), flush=True)
            elif action.kind == "status":
                print(json.dumps(self.status(), indent=2), flush=True)
            elif action.message != "empty line":
                log.warning("ignored input: %s", action.message)
        log.info("console input closed")

    def start_http(self) -> threading.Thread:
        h = self.cfg["http"]
        handler = _make_handler(self, self.cfg.get("_http_token"), bool(h.get("drive_relay", True)))
        self.http_server = http.server.ThreadingHTTPServer((h.get("bind", "127.0.0.1"), int(h.get("port", 8765))), handler)
        self.http_server.daemon_threads = True
        t = threading.Thread(target=self.http_server.serve_forever, name="http-input", daemon=True)
        t.start()
        log.info("local HTTP input listening on http://%s:%s (POST /scan, /event, /exit-button, /alarm, "
                 "/commands/<id>/ack; GET /status, /commands)", h.get("bind"), self.http_server.server_address[1])
        return t


def _make_handler(bridge: Bridge, token: str | None, drive_relay: bool):
    class Handler(http.server.BaseHTTPRequestHandler):
        server_version = "gymops-gate-bridge/" + VERSION

        def log_message(self, fmt: str, *args: Any) -> None:  # route through logging, quietly
            log.debug("http %s - " + fmt, self.client_address[0], *args)

        def _send(self, status: int, payload: Any) -> None:
            data = json.dumps(payload).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _authorised(self) -> bool:
            if token and not hmac.compare_digest(self.headers.get("X-Bridge-Token", ""), token):
                self._send(401, {"error": "missing or wrong X-Bridge-Token"})
                return False
            return True

        def _body(self) -> dict | None:
            length = int(self.headers.get("Content-Length") or 0)
            if length > 16384:
                self._send(413, {"error": "body too large"})
                return None
            try:
                data = json.loads(self.rfile.read(length) or b"{}")
            except json.JSONDecodeError:
                self._send(400, {"error": "body must be JSON"})
                return None
            if not isinstance(data, dict):
                self._send(400, {"error": "body must be a JSON object"})
                return None
            return data

        def do_GET(self) -> None:  # noqa: N802
            if not self._authorised():
                return
            path = urllib.parse.urlparse(self.path).path.rstrip("/")
            if path in ("", "/status"):
                self._send(200, bridge.status())
            elif path == "/commands":
                self._send(200, {"commands": list(bridge.state.pending_commands.values())})
            else:
                self._send(404, {"error": "not found"})

        def do_POST(self) -> None:  # noqa: N802
            if not self._authorised():
                return
            path = urllib.parse.urlparse(self.path).path.rstrip("/")
            body = self._body()
            if body is None:
                return
            m = re.fullmatch(r"/commands/(\d+)/ack", path)
            if m:
                self._send(200, bridge.ack_command(int(m.group(1)), bool(body.get("success", True)),
                                                   body.get("templateRef"), body.get("message")))
                return
            event = str(body.get("event") or "").upper()
            if path == "/exit-button" or event in ("EXIT_BUTTON", "EXIT-BUTTON"):
                self._send(200, bridge.handle_exit_button("http", drive_relay))
                return
            if path == "/alarm" or event == "ALARM":
                result = bridge.handle_alarm(str(body.get("type") or "").upper(), body.get("details"))
                self._send(400 if "error" in result else 200, result)
                return
            if path not in ("", "/scan", "/event"):
                self._send(404, {"error": "not found"})
                return
            ctype = str(body.get("credentialType") or "").upper()
            value = str(body.get("credentialValue") or "").strip()
            direction = body.get("direction")
            direction = str(direction).upper() if direction else None
            if ctype not in CREDENTIAL_TYPES or not value or len(value) > 128:
                self._send(400, {"error": f"credentialType must be one of {', '.join(CREDENTIAL_TYPES)} and "
                                          "credentialValue 1-128 characters"})
                return
            if direction not in (None, *DIRECTIONS):
                self._send(400, {"error": "direction must be ENTRY or EXIT"})
                return
            self._send(200, bridge.handle_scan(ctype, value, direction, "http", drive_relay))

    return Handler


# ---------------------------------------------------------------------------------------------------------- self-test

def self_test(cfg: dict) -> int:
    sys.stdout.reconfigure(line_buffering=True) if hasattr(sys.stdout, "reconfigure") else None
    client = GateClient(cfg["server_url"], cfg["device_id"], cfg["_secret"], float(cfg["request_timeout_seconds"]))
    body = {"firmwareVersion": str(cfg["firmware_version"])[:40], "uptimeSeconds": 0, "offlineQueueDepth": 0}
    raw = encode_body(body)
    path = client.path_prefix + GATE_PREFIX + "/heartbeat"
    ts, nonce = str(int(time.time())), secrets.token_hex(16)
    print(f"Server:        {cfg['server_url']}")
    print(f"Device id:     {cfg['device_id']}")
    print(f"Secret:        loaded ({len(cfg['_secret'])} characters, not shown)")
    print(f"Canonical:     {canonical('POST', path, ts, nonce, raw)}")
    print("Signature:     hex(HMAC-SHA256(secret, canonical)) -> X-Signature header")
    ok = True
    try:
        resp = client.call("POST", GATE_PREFIX + "/heartbeat", body)
    except ServerUnavailable as e:
        print(f"Heartbeat:     FAILED - server unreachable ({e}). Check the URL, internet and firewall (outbound 443).")
        return 1
    print(f"Heartbeat:     HTTP {resp.status}, response signature {'verified' if resp.verified else 'NOT verified'}")
    if not resp.ok:
        print(f"               {resp.error_message()}")
        if resp.status == 401:
            print("               -> wrong device id / secret, or this computer's clock is more than 5 minutes off")
        elif resp.feature_disabled:
            print("               -> gates are switched off for this gym: Settings -> Gate access control")
        return 1
    data = resp.body
    server_time = parse_instant(data.get("serverTime"))
    if server_time:
        offset = (utc_now() - server_time).total_seconds()
        print(f"Clock:         {offset:+.1f}s compared with the server {'(OK)' if abs(offset) < 60 else '(FIX: enable NTP)'}")
        ok = ok and abs(offset) < 300
    print(f"Device status: {data.get('deviceStatus')}, pending commands: {data.get('pendingCommands')}")
    print(f"Exit mode:     {data.get('exitMode') or '(not reported by this server version)'}")
    bridge = Bridge(cfg, dry_run=True, client=client)
    try:
        allowlist = bridge.refresh_allowlist()
        if allowlist is None:
            print("Allowlist:     FAILED (see the log above)")
            ok = False
        else:
            print(f"Allowlist:     {len(allowlist)} credentials, signature verified, valid until "
                  f"{allowlist.data.get('validUntil')}, time zone {allowlist.data.get('timezone')}")
    except ServerUnavailable as e:
        print(f"Allowlist:     FAILED ({e})")
        ok = False
    finally:
        bridge.shutdown()
    print("RESULT:        " + ("OK - the bridge can talk to GymOps" if ok else "PROBLEM - see above"))
    return 0 if ok else 1


# --------------------------------------------------------------------------------------------------------------- main

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--config", help="JSON config file (see scripts/gate_bridge.example.json)")
    p.add_argument("--server-url", help="GymOps address, e.g. https://gym.example.com")
    p.add_argument("--device-id", help="device id from Access control -> Devices (gd_...)")
    p.add_argument("--secret-env", dest="device_secret_env",
                   help="environment variable holding the device secret (default GATE_DEVICE_SECRET)")
    p.add_argument("--secret-file", dest="device_secret_file", help="file holding the device secret (chmod 600)")
    p.add_argument("--direction", choices=DIRECTIONS, help="direction reported for scans (default: the device's)")
    p.add_argument("--input", dest="inputs", action="append", choices=["keyboard", "http", "exit-button"],
                   help="input to enable (repeatable); overrides the config file")
    p.add_argument("--http-bind", help="address for the local HTTP input (default 127.0.0.1)")
    p.add_argument("--http-port", type=int, help="port for the local HTTP input (default 8765)")
    p.add_argument("--relay", choices=["print", "command", "http", "none"], help="relay type (details in the config)")
    p.add_argument("--state-dir", help="folder for the cached allowlist and the offline event buffer")
    p.add_argument("--heartbeat-seconds", type=int, help="heartbeat interval (default 60)")
    p.add_argument("--dry-run", action="store_true",
                   help="talk to the server normally but never switch the relay / deny signal or forward commands")
    p.add_argument("--self-test", action="store_true", help="sign a heartbeat, fetch the allowlist, print the result")
    p.add_argument("--log-level", choices=["DEBUG", "INFO", "WARNING", "ERROR"])
    p.add_argument("--version", action="version", version="gymops-gate-bridge " + VERSION)
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    overrides: dict[str, Any] = {
        "server_url": args.server_url, "device_id": args.device_id, "device_secret_env": args.device_secret_env,
        "device_secret_file": args.device_secret_file, "direction": args.direction, "inputs": args.inputs,
        "state_dir": args.state_dir, "heartbeat_seconds": args.heartbeat_seconds, "log_level": args.log_level,
    }
    http_over = {k: v for k, v in {"bind": args.http_bind, "port": args.http_port}.items() if v is not None}
    if http_over:
        overrides["http"] = http_over
    if args.relay:
        overrides["relay"] = {"type": args.relay}
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-7s %(message)s", datefmt="%H:%M:%S")
    try:
        cfg = load_config(args.config, overrides)
    except ConfigError as e:
        log.error("%s", e)
        return 2
    logging.getLogger().setLevel(cfg.get("log_level") or "INFO")
    if args.self_test:
        return self_test(cfg)

    bridge = Bridge(cfg, dry_run=args.dry_run)
    if bridge.allowlist.load() is not None:
        log.info("loaded cached offline allowlist (%d credentials)", len(bridge.allowlist.get() or []))
    log.info("GymOps gate bridge %s: device %s -> %s%s", VERSION, cfg["device_id"], cfg["server_url"],
             " [dry-run: relay disabled]" if args.dry_run else "")

    def on_signal(signum, _frame):
        log.info("stopping (signal %s)", signum)
        bridge.stop.set()

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            signal.signal(sig, on_signal)
        except (ValueError, OSError, AttributeError):  # pragma: no cover - not in the main thread / platform
            pass

    bridge.start_background()
    inputs = cfg.get("inputs") or []
    if "http" in inputs:
        try:
            bridge.start_http()
        except OSError as e:
            log.error("cannot start the local HTTP input: %s", e)
            return 2
    console = "keyboard" in inputs or "exit-button" in inputs
    if console:
        t = threading.Thread(target=bridge.run_keyboard, args=("exit-button" in inputs,), name="console", daemon=True)
        t.start()
    try:
        while not bridge.stop.is_set():
            if console and not t.is_alive() and "http" not in inputs:
                break          # console input ended (e.g. piped input) and nothing else to serve
            bridge.stop.wait(0.5)
    except KeyboardInterrupt:
        pass
    # last chance to upload what was buffered, then switch everything off cleanly
    try:
        bridge.upload_buffer()
    except ServerUnavailable:
        log.info("%d offline events stay buffered for the next start", bridge.buffer.depth())
    bridge.shutdown()
    log.info("stopped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
