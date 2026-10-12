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
    get_attendance_repository,
    get_branch_repository,
    get_member_repository,
    get_revenue_repository,
)
from app.core.config import Settings, get_settings
from app.core.security import Principal, require_analytics_access
from app.repositories.attendance import AttendanceRepository
from app.repositories.base import utc_window
from app.repositories.branches import BranchRepository
from app.repositories.members import MemberRepository
from app.repositories.revenue import RevenueRepository
from app.schemas.branches import BranchesResponse
from app.services.branches import compute_branches

router = APIRouter(prefix="/analytics", tags=["branches"])


@router.get(
    "/branches",
    response_model=BranchesResponse,
    responses=ERROR_RESPONSES,
    summary="Side-by-side branch comparison ranked by net revenue",
)
def branches(
    branch_id: BranchIdParam = None,
    date_from: FromParam = None,
    date_to: ToParam = None,
    principal: Principal = Depends(require_analytics_access),
    settings: Settings = Depends(get_settings),
    branch_repo: BranchRepository = Depends(get_branch_repository),
    member_repo: MemberRepository = Depends(get_member_repository),
    attendance_repo: AttendanceRepository = Depends(get_attendance_repository),
    revenue_repo: RevenueRepository = Depends(get_revenue_repository),
) -> dict[str, Any]:
    """Per branch: active and new members (home branch), visits, visits per active member, net revenue
    (invoice branch), revenue per active member, peak average hourly occupancy vs capacity, currently
    open complaints, and rank by net revenue (1 = highest)."""
    ctx = build_context(principal, settings, branch_id, date_from, date_to)

    def compute() -> dict[str, Any]:
        b = ctx.scope.branch_ids
        window = utc_window(ctx.date_from, ctx.date_to)
        return compute_branches(
            branch_repo.fetch_branches(b),
            member_repo.fetch_members(b),
            member_repo.fetch_subscriptions(b),
            attendance_repo.fetch_visits(*window, b),
            revenue_repo.fetch_payments(*window, b),
            revenue_repo.fetch_refunds(*window, b),
            branch_repo.fetch_open_complaints(b),
            ctx.date_from,
            ctx.date_to,
            ctx.today,
            ctx.tz,
        )

    return cached_response("branches", ctx, compute)
