from __future__ import annotations

import logging
from collections.abc import Callable

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse

from app.api.deps import get_db_check
from app.schemas.health import HealthResponse

router = APIRouter(tags=["health"])
logger = logging.getLogger("app.health")


@router.get(
    "/health",
    response_model=HealthResponse,
    response_model_exclude_none=True,
    summary="Liveness (no dependencies checked)",
)
def health() -> dict:
    return {"status": "UP"}


@router.get(
    "/health/ready",
    response_model=HealthResponse,
    summary="Readiness: database reachable",
    responses={503: {"model": HealthResponse, "description": "Database unreachable"}},
)
def ready(check: Callable[[], None] = Depends(get_db_check)):
    try:
        check()
    except Exception as exc:  # any failure means "not ready"
        logger.warning("readiness check failed", extra={"error": type(exc).__name__})
        return JSONResponse(status_code=503, content={"status": "DOWN", "checks": {"database": "DOWN"}})
    return {"status": "UP", "checks": {"database": "UP"}}
