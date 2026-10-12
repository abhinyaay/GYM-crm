from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends

from app.api.deps import (
    ERROR_RESPONSES,
    BranchIdParam,
    FromParam,
    GranularityParam,
    ToParam,
    build_context,
    cached_response,
    get_attendance_repository,
    get_branch_repository,
)
from app.core.config import Settings, get_settings
from app.core.security import Principal, require_analytics_access
from app.repositories.attendance import AttendanceRepository
from app.repositories.base import utc_window
from app.repositories.branches import BranchRepository
from app.schemas.attendance import AttendanceResponse, PeakHoursResponse
from app.services.attendance import compute_attendance, compute_peak_hours
from app.services.common import Granularity

router = APIRouter(prefix="/analytics", tags=["attendance"])


@router.get(
    "/attendance",
    response_model=AttendanceResponse,
    responses=ERROR_RESPONSES,
    summary="Visits, unique members and visit duration per period",
)
def attendance(
    branch_id: BranchIdParam = None,
    date_from: FromParam = None,
    date_to: ToParam = None,
    granularity: GranularityParam = Granularity.day,
    principal: Principal = Depends(require_analytics_access),
    settings: Settings = Depends(get_settings),
    repo: AttendanceRepository = Depends(get_attendance_repository),
) -> dict[str, Any]:
    """Visit counts and distinct members per period (branch local dates), average visit duration in
    minutes (auto-checked-out and open visits excluded), visits by weekday, and summary totals."""
    ctx = build_context(principal, settings, branch_id, date_from, date_to, granularity)

    def compute() -> dict[str, Any]:
        visits = repo.fetch_visits(*utc_window(ctx.date_from, ctx.date_to), ctx.scope.branch_ids)
        return compute_attendance(visits, ctx.date_from, ctx.date_to, ctx.granularity)

    return cached_response("attendance", ctx, compute)


@router.get(
    "/attendance/peak-hours",
    response_model=PeakHoursResponse,
    responses=ERROR_RESPONSES,
    summary="Weekday x hour check-in heatmap, peak windows and occupancy",
)
def peak_hours(
    branch_id: BranchIdParam = None,
    date_from: FromParam = None,
    date_to: ToParam = None,
    principal: Principal = Depends(require_analytics_access),
    settings: Settings = Depends(get_settings),
    repo: AttendanceRepository = Depends(get_attendance_repository),
    branches: BranchRepository = Depends(get_branch_repository),
) -> dict[str, Any]:
    """7x24 heatmap (ISO weekday x **branch-local** hour) of the average number of check-ins per
    occurrence of that weekday in the range, the top 3 peak hour windows, and - when exactly one
    branch is selected - the average concurrent occupancy per local hour vs branch capacity."""
    ctx = build_context(principal, settings, branch_id, date_from, date_to)

    def compute() -> dict[str, Any]:
        visits = repo.fetch_visits(*utc_window(ctx.date_from, ctx.date_to), ctx.scope.branch_ids)
        branch = None
        if ctx.scope.is_single_branch:
            rows = branches.fetch_branches(ctx.scope.branch_ids)
            if len(rows):
                branch = rows.iloc[0].to_dict()
        return compute_peak_hours(visits, ctx.date_from, ctx.date_to, branch)

    return cached_response("peak-hours", ctx, compute)
