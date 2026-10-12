"""Pure-ASGI middleware: correlation id propagation, request logging and last-resort error handling.

* Reads ``X-Request-Id`` or ``X-Correlation-Id`` from the request, otherwise generates a UUID.
* Echoes it back as ``X-Request-Id`` and exposes it to logs / error bodies through a contextvar.
* Logs one structured line per request (method, path, status, duration_ms).
* Converts any unhandled exception into a JSON ``INTERNAL_ERROR`` body (never leaks stack traces).
"""

from __future__ import annotations

import json
import logging
import re
import time
import uuid

from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.core.context import correlation_id_var
from app.core.errors import INTERNAL_ERROR, error_body

logger = logging.getLogger("app.request")

_VALID_ID = re.compile(r"^[A-Za-z0-9._:-]{1,64}$")
_QUIET_PATHS = {"/health", "/health/ready", "/metrics"}


def _incoming_id(scope: Scope) -> str | None:
    headers = dict(scope.get("headers") or [])
    for name in (b"x-request-id", b"x-correlation-id"):
        raw = headers.get(name)
        if raw:
            value = raw.decode("latin-1").strip()
            if _VALID_ID.match(value):
                return value
    return None


class CorrelationIdMiddleware:
    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        cid = _incoming_id(scope) or str(uuid.uuid4())
        correlation_id_var.set(cid)
        scope.setdefault("state", {})["correlation_id"] = cid

        status_holder = {"status": 500, "started": False}
        start = time.perf_counter()

        async def send_wrapper(message: Message) -> None:
            if message["type"] == "http.response.start":
                status_holder["status"] = message["status"]
                status_holder["started"] = True
                headers = [(k, v) for k, v in message.get("headers", []) if k.lower() != b"x-request-id"]
                headers.append((b"x-request-id", cid.encode("latin-1")))
                message["headers"] = headers
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
        except Exception:
            logger.exception("unhandled error")
            if not status_holder["started"]:
                body = json.dumps(
                    error_body(500, INTERNAL_ERROR, "Unexpected internal error", scope.get("path", ""), cid)
                ).encode()
                await send_wrapper(
                    {
                        "type": "http.response.start",
                        "status": 500,
                        "headers": [
                            (b"content-type", b"application/json"),
                            (b"content-length", str(len(body)).encode()),
                        ],
                    }
                )
                await send_wrapper({"type": "http.response.body", "body": body})
        finally:
            path = scope.get("path", "")
            duration_ms = round((time.perf_counter() - start) * 1000, 2)
            level = logging.DEBUG if path in _QUIET_PATHS and status_holder["status"] < 400 else logging.INFO
            logger.log(
                level,
                "request completed",
                extra={
                    "method": scope.get("method"),
                    "path": path,
                    "status": status_holder["status"],
                    "duration_ms": duration_ms,
                },
            )
