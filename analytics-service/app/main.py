"""Application factory for the GymOps analytics service (read-only, Pandas-based)."""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from prometheus_fastapi_instrumentator import Instrumentator

from app.api.routers import (
    attendance,
    branches,
    classes,
    growth,
    health,
    leads,
    members,
    memberships,
    revenue,
    trainers,
)
from app.core.config import Settings, get_settings
from app.core.errors import register_exception_handlers
from app.core.logging import setup_logging
from app.db.session import dispose_engine
from app.middleware.correlation import CorrelationIdMiddleware

logger = logging.getLogger("app")

DESCRIPTION = """
Read-only analytics for the GymOps platform. All business data is owned and written by the Java core
service; this service reads it through a read-only PostgreSQL role and computes aggregates with Pandas.

Authenticate with the access token issued by the core service (`Authorization: Bearer <JWT>`).
Allowed roles: SUPER_ADMIN, GYM_ADMIN (all branches) and MANAGER (assigned branches only).
Responses are cached in-process for `CACHE_TTL_SECONDS` (default 300 s), so figures may be a few
minutes stale.
"""


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    logger.info("analytics service starting")
    yield
    dispose_engine()
    logger.info("analytics service stopped")


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()  # fails fast on missing / weak configuration
    setup_logging(settings.log_level)

    app = FastAPI(
        title="GymOps Analytics Service",
        version="1.0.0",
        description=DESCRIPTION,
        lifespan=lifespan,
        docs_url="/docs",
        redoc_url=None,
        openapi_url="/openapi.json",
    )
    register_exception_handlers(app)

    for module in (
        attendance,
        revenue,
        members,
        memberships,
        trainers,
        branches,
        classes,
        leads,
        growth,
        health,
    ):
        app.include_router(module.router)

    Instrumentator(
        excluded_handlers=["/metrics", "/health", "/health/ready"],
        should_group_status_codes=True,
    ).instrument(app).expose(app, endpoint="/metrics", include_in_schema=False)

    # Added last so it is the outermost user middleware: every response (including errors and
    # metrics) carries X-Request-Id and every log line the correlation id.
    app.add_middleware(CorrelationIdMiddleware)
    return app


app = create_app()
