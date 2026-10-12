from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends

from app.api.deps import (
    ERROR_RESPONSES,
    BranchIdParam,
    FromParam,
    ToParam,
    build_context,
    cached_response,
)
from app.core.config import Settings, get_settings
from app.core.security import Principal, require_analytics_access
from app.db.session import get_engine
from app.repositories.base import utc_window
from app.repositories.classes import ClassesRepository
from app.schemas.classes import ClassesResponse
from app.services.classes import compute_classes

router = APIRouter(prefix="/analytics", tags=["classes"])


def get_classes_repository() -> ClassesRepository:
    """One repository per request (the engine/pool is process-wide); overridable in tests."""
    return ClassesRepository(get_engine())


@router.get(
    "/classes",
    response_model=ClassesResponse,
    responses=ERROR_RESPONSES,
    summary="Group-class fill, attendance, late cancels, waitlist, time slots and instructors",
)
def classes(
    branch_id: BranchIdParam = None,
    date_from: FromParam = None,
    date_to: ToParam = None,
    principal: Principal = Depends(require_analytics_access),
    settings: Settings = Depends(get_settings),
    repo: ClassesRepository = Depends(get_classes_repository),
) -> dict[str, Any]:
    """Sessions are attributed to their branch and bucketed by the business-local date/hour of their
    start. CANCELLED sessions only count towards cancelledSessions; all other metrics cover
    non-cancelled sessions. occupied = bookings BOOKED + ATTENDED + NO_SHOW; fillRate = occupied /
    capacity (0..1); attendanceRate = ATTENDED / (ATTENDED + NO_SHOW); lateCancelRate =
    LATE_CANCELLED / non-WAITLISTED bookings; waitlistJoins = bookings WAITLISTED or ever promoted;
    waitlistPromotions = promoted bookings; sessionsFull = sessions with occupied >= capacity;
    timeSlots = ISO weekday (1=Mon..7=Sun) x local start hour; instructor avgFillRate = mean of
    per-session fill rates; daily lists every date in the range (zeros for empty days)."""
    ctx = build_context(principal, settings, branch_id, date_from, date_to)

    def compute() -> dict[str, Any]:
        b = ctx.scope.branch_ids
        window = utc_window(ctx.date_from, ctx.date_to)
        return compute_classes(
            repo.fetch_class_types(),
            repo.fetch_sessions(*window, b),
            repo.fetch_bookings(*window, b),
            repo.fetch_instructors(*window, b),
            ctx.date_from,
            ctx.date_to,
            ctx.tz,
        )

    return cached_response("classes", ctx, compute)
