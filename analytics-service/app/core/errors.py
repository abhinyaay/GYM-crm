"""Error model and exception handlers.

Error bodies match the Java core service::

    {"timestamp": "...", "status": 403, "error": "Forbidden", "code": "ACCESS_DENIED",
     "message": "...", "path": "/analytics/...", "correlationId": "..."}
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime
from http import HTTPStatus

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy.exc import DBAPIError, InterfaceError, OperationalError, SQLAlchemyError
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.core.context import get_correlation_id

logger = logging.getLogger("app.errors")

UNAUTHORIZED = "UNAUTHORIZED"
ACCESS_DENIED = "ACCESS_DENIED"
VALIDATION_FAILED = "VALIDATION_FAILED"
ANALYTICS_UNAVAILABLE = "ANALYTICS_UNAVAILABLE"
INTERNAL_ERROR = "INTERNAL_ERROR"
NOT_FOUND = "NOT_FOUND"


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str, headers: dict[str, str] | None = None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.headers = headers


class UnauthorizedError(ApiError):
    def __init__(self, message: str = "Authentication required"):
        super().__init__(401, UNAUTHORIZED, message, {"WWW-Authenticate": "Bearer"})


class AccessDeniedError(ApiError):
    def __init__(self, message: str = "Access denied"):
        super().__init__(403, ACCESS_DENIED, message)


class ValidationFailedError(ApiError):
    def __init__(self, message: str):
        super().__init__(400, VALIDATION_FAILED, message)


def _correlation_id(request: Request) -> str | None:
    return getattr(request.state, "correlation_id", None) or get_correlation_id()


def error_body(status: int, code: str, message: str, path: str, correlation_id: str | None) -> dict:
    try:
        reason = HTTPStatus(status).phrase
    except ValueError:
        reason = "Error"
    return {
        "timestamp": datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
        "status": status,
        "error": reason,
        "code": code,
        "message": message,
        "path": path,
        "correlationId": correlation_id,
    }


def error_response(
    request: Request, status: int, code: str, message: str, headers: dict[str, str] | None = None
) -> JSONResponse:
    cid = _correlation_id(request)
    all_headers = dict(headers or {})
    if cid:
        all_headers["X-Request-Id"] = cid
    return JSONResponse(
        status_code=status,
        content=error_body(status, code, message, request.url.path, cid),
        headers=all_headers,
    )


def _format_validation(exc: RequestValidationError) -> str:
    parts = []
    for err in exc.errors():
        loc = [str(p) for p in err.get("loc", ()) if p not in ("query", "path", "body", "header")]
        field = ".".join(loc) or "request"
        parts.append(f"{field}: {err.get('msg', 'invalid value')}")
    return "; ".join(parts) or "Invalid request"


def register_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def _api_error(request: Request, exc: ApiError) -> JSONResponse:
        return error_response(request, exc.status, exc.code, exc.message, exc.headers)

    @app.exception_handler(RequestValidationError)
    async def _validation(request: Request, exc: RequestValidationError) -> JSONResponse:
        return error_response(request, 400, VALIDATION_FAILED, _format_validation(exc))

    @app.exception_handler(StarletteHTTPException)
    async def _http(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        code = {401: UNAUTHORIZED, 403: ACCESS_DENIED, 404: NOT_FOUND}.get(exc.status_code, "HTTP_ERROR")
        if exc.status_code == 405:
            code = "METHOD_NOT_ALLOWED"
        message = exc.detail if isinstance(exc.detail, str) else HTTPStatus(exc.status_code).phrase
        return error_response(request, exc.status_code, code, message, getattr(exc, "headers", None))

    async def _db_unavailable(request: Request, exc: Exception) -> JSONResponse:
        logger.warning("analytics database unavailable", extra={"error": type(exc).__name__})
        return error_response(
            request, 503, ANALYTICS_UNAVAILABLE, "Analytics data source is temporarily unavailable"
        )

    app.add_exception_handler(OperationalError, _db_unavailable)
    app.add_exception_handler(InterfaceError, _db_unavailable)

    @app.exception_handler(DBAPIError)
    async def _dbapi(request: Request, exc: DBAPIError) -> JSONResponse:
        if exc.connection_invalidated:
            return await _db_unavailable(request, exc)
        logger.error("database error", exc_info=exc)
        return error_response(request, 500, INTERNAL_ERROR, "Unexpected error while computing analytics")

    @app.exception_handler(SQLAlchemyError)
    async def _sqlalchemy(request: Request, exc: SQLAlchemyError) -> JSONResponse:
        logger.error("database error", exc_info=exc)
        return error_response(request, 500, INTERNAL_ERROR, "Unexpected error while computing analytics")
