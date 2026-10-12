#!/usr/bin/env python3
"""Command-line gate device for GymOps (stdlib only). Signs every request exactly like gate firmware / a vendor bridge.

Device credentials come from the "Register device" / "Rotate secret" dialog in the web app (Access control):

    export GATE_DEVICE_ID=gd_...        # X-Device-Id
    export GATE_DEVICE_SECRET=...       # 64 hex chars, shown once
    export BASE_URL=http://localhost    # nginx (or http://localhost:8081 for core directly)

Examples:
    python3 scripts/gate_simulator.py heartbeat
    python3 scripts/gate_simulator.py scan --type RFID_CARD --value D300002A               # demo card of member 42
    python3 scripts/gate_simulator.py scan --type RFID_CARD --value D300002A --direction EXIT
    python3 scripts/gate_simulator.py scan --type FINGERPRINT --value FP-DEMO-42
    python3 scripts/gate_simulator.py scan --type QR --value 'GQ1....'                      # from the member app
    python3 scripts/gate_simulator.py allowlist --out allowlist.json                        # signed offline allowlist
    python3 scripts/gate_simulator.py offline --type RFID_CARD --value D300002A --minutes-ago 50 --stay 45
    python3 scripts/gate_simulator.py commands                                              # enrol / delete queue
    python3 scripts/gate_simulator.py ack 17 --template-ref FP-ABC123                       # complete an enrolment
    python3 scripts/gate_simulator.py alarm --type DOOR_FORCED

Signature: X-Signature = hex(HMAC-SHA256(secret, METHOD|PATH|X-Timestamp|X-Nonce|hex(SHA-256(body)))), X-Timestamp in
epoch seconds. Responses are verified: X-Signature = hex(HMAC(secret, "response|" + nonce + "|" + hex(SHA-256(body)))).
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import hmac
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.request


class Gate:
    def __init__(self, base_url: str, device_id: str, secret: str, clock_offset: int = 0) -> None:
        self.base = base_url.rstrip("/")
        self.device_id = device_id
        self.secret = secret.encode()
        self.clock_offset = clock_offset

    def request(self, method: str, path: str, body: object | None = None) -> tuple[int, object, bool]:
        raw = b"" if body is None else json.dumps(body, separators=(",", ":")).encode()
        timestamp = str(int(time.time()) + self.clock_offset)
        nonce = secrets.token_hex(16)
        canonical = f"{method}|{path}|{timestamp}|{nonce}|{hashlib.sha256(raw).hexdigest()}"
        signature = hmac.new(self.secret, canonical.encode(), hashlib.sha256).hexdigest()
        headers = {"Accept": "application/json", "X-Device-Id": self.device_id, "X-Timestamp": timestamp,
                   "X-Nonce": nonce, "X-Signature": signature}
        if body is not None:
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(self.base + path, data=raw if body is not None else None, method=method,
                                     headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=15) as res:
                payload = res.read()
                expected = hmac.new(self.secret, f"response|{nonce}|{hashlib.sha256(payload).hexdigest()}".encode(),
                                    hashlib.sha256).hexdigest()
                verified = hmac.compare_digest(expected, res.headers.get("X-Signature", ""))
                return res.status, json.loads(payload or b"null"), verified
        except urllib.error.HTTPError as e:
            text = e.read().decode(errors="replace")
            try:
                return e.code, json.loads(text), False
            except json.JSONDecodeError:
                return e.code, text, False


def event_id(prefix: str = "cli") -> str:
    return f"{prefix}-{int(time.time() * 1000):x}-{secrets.token_hex(3)}"


def iso(t: dt.datetime) -> str:
    return t.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def show(status: int, body: object, verified: bool) -> int:
    print(f"HTTP {status}  response signature: {'verified' if verified else 'NOT verified'}")
    print(json.dumps(body, indent=2))
    if isinstance(body, dict) and "decision" in body:
        colour = "\033[92m" if body["decision"] == "GRANTED" else "\033[91m"
        verdict = "OPEN" if body.get("openGate") else body["decision"]
        print(f"{colour}{verdict}\033[0m  {body.get('reasonCode')}  {body.get('memberDisplayName') or ''}")
    return 0 if 200 <= status < 300 else 1


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base-url", default=os.environ.get("BASE_URL", "http://localhost"))
    p.add_argument("--device-id", default=os.environ.get("GATE_DEVICE_ID"))
    p.add_argument("--secret", default=os.environ.get("GATE_DEVICE_SECRET"))
    p.add_argument("--clock-offset", type=int, default=0, help="seconds added to X-Timestamp (to demo skew rejection)")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("heartbeat")
    scan = sub.add_parser("scan")
    scan.add_argument("--type", required=True, choices=["RFID_CARD", "FINGERPRINT", "FACE", "QR"])
    scan.add_argument("--value", required=True)
    scan.add_argument("--direction", choices=["ENTRY", "EXIT"])
    scan.add_argument("--event-id", help="reuse to demonstrate idempotency")
    off = sub.add_parser("offline", help="upload an offline entry + exit pair decided by the gate while disconnected")
    off.add_argument("--type", required=True, choices=["RFID_CARD", "FINGERPRINT", "FACE"])
    off.add_argument("--value", required=True)
    off.add_argument("--minutes-ago", type=int, default=60, help="when the member entered")
    off.add_argument("--stay", type=int, default=45, help="minutes inside before exiting (0 = still inside)")
    al = sub.add_parser("allowlist")
    al.add_argument("--out", help="write the signed allowlist (body + signature) to this file")
    sub.add_parser("commands")
    ack = sub.add_parser("ack")
    ack.add_argument("command_id", type=int)
    ack.add_argument("--template-ref", help="device template reference (required for a successful enrolment)")
    ack.add_argument("--fail", action="store_true")
    alarm = sub.add_parser("alarm")
    alarm.add_argument("--type", required=True, choices=["DOOR_FORCED", "DOOR_HELD_OPEN", "TAILGATING", "TAMPER"])
    alarm.add_argument("--details")
    args = p.parse_args()

    if not args.device_id or not args.secret:
        p.error("set GATE_DEVICE_ID and GATE_DEVICE_SECRET (or --device-id / --secret)")
    gate = Gate(args.base_url, args.device_id, args.secret, args.clock_offset)

    if args.cmd == "heartbeat":
        return show(*gate.request("POST", "/api/v1/access/gate/heartbeat",
                                  {"firmwareVersion": "cli-1.0", "uptimeSeconds": int(time.monotonic())}))
    if args.cmd == "scan":
        body = {"deviceEventId": args.event_id or event_id(), "credentialType": args.type, "credentialValue": args.value}
        if args.direction:
            body["direction"] = args.direction
        return show(*gate.request("POST", "/api/v1/access/gate/events", body))
    if args.cmd == "offline":
        entered = dt.datetime.now(dt.timezone.utc) - dt.timedelta(minutes=args.minutes_ago)
        events = [{"deviceEventId": event_id("off"), "credentialType": args.type, "credentialValue": args.value,
                   "direction": "ENTRY", "occurredAt": iso(entered), "offlineDecision": "GRANTED"}]
        if args.stay > 0:
            events.append({"deviceEventId": event_id("off"), "credentialType": args.type, "credentialValue": args.value,
                           "direction": "EXIT", "occurredAt": iso(entered + dt.timedelta(minutes=args.stay)),
                           "offlineDecision": "GRANTED"})
        return show(*gate.request("POST", "/api/v1/access/gate/events/batch", {"events": events}))
    if args.cmd == "allowlist":
        status, body, verified = gate.request("GET", "/api/v1/access/gate/allowlist")
        if args.out and status == 200:
            with open(args.out, "w", encoding="utf-8") as f:
                json.dump({"signatureVerified": verified, "allowlist": body}, f, indent=2)
        if isinstance(body, dict) and "entries" in body:
            print(f"{len(body['entries'])} entries valid until {body['validUntil']}; signature "
                  f"{'verified' if verified else 'NOT verified'}")
            return 0 if verified else 1
        return show(status, body, verified)
    if args.cmd == "commands":
        return show(*gate.request("GET", "/api/v1/access/gate/commands"))
    if args.cmd == "ack":
        return show(*gate.request("POST", f"/api/v1/access/gate/commands/{args.command_id}/ack",
                                  {"success": not args.fail, "templateRef": args.template_ref,
                                   "message": "acknowledged by gate_simulator.py"}))
    if args.cmd == "alarm":
        return show(*gate.request("POST", "/api/v1/access/gate/alarms",
                                  {"deviceEventId": event_id("alarm"), "type": args.type, "details": args.details}))
    return 2


if __name__ == "__main__":
    sys.exit(main())
