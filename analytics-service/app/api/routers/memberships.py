from __future__ import annotations

import re
from datetime import date
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Query

from app.api.deps import (
    ERROR_RESPONSES,
    BranchIdParam,
    FromParam,
    ToParam,
    build_context,
    cached_response,
    get_member_repository,
)
from app.core.config import Settings, get_settings
from app.core.errors import ValidationFailedError
from app.core.security import Principal, require_analytics_access
from app.repositories.members import MemberRepository
from app.schemas.memberships import ChurnSummaryResponse, MembershipsResponse
from app.services.common import Granularity
from app.services.memberships import compute_memberships, month_summary

router = APIRouter(prefix="/analytics", tags=["memberships"])

_MONTH = re.compile(r"^(\d{4})-(\d{2})$")


@router.get(
    "/memberships",
    response_model=MembershipsResponse,
    responses=ERROR_RESPONSES,
    summary="Renewal and churn rates per month, plan mix, expiries, freezes, cancellations",
)
def memberships(
    branch_id: BranchIdParam = None,
    date_from: FromParam = None,
    date_to: ToParam = None,
    principal: Principal = Depends(require_analytics_access),
    settings: Settings = Depends(get_settings),
    repo: MemberRepository = Depends(get_member_repository),
) -> dict[str, Any]:
    """Monthly figures for every calendar month intersecting [from, to] (up to the current month).

    * renewalRate = renewed / ended, where ended = ACTIVE/FROZEN/GRACE/EXPIRED/SUPERSEDED subscriptions
      whose end_date is in the month (and not in the future) and renewed = those referenced by a
      committed successor's previous_subscription_id.
    * churnRate = churned / membersAtStart, where membersAtStart holds committed access on the 1st and
      churned = those whose access ended in the month with no committed subscription starting within
      the ending plan's grace_period_days (+1 day). Undecided cases (grace still open) are `pendingMembers`.
    * Plan mix / upcoming expiries / freeze usage are as of today; cancellations by cancelled_at in range.
    Subscriptions are attributed to the member's home branch.
    """
    ctx = build_context(principal, settings, branch_id, date_from, date_to, Granularity.month)

    def compute() -> dict[str, Any]:
        return compute_memberships(
            repo.fetch_subscriptions(ctx.scope.branch_ids), ctx.date_from, ctx.date_to, ctx.today, ctx.tz
        )

    return cached_response("memberships", ctx, compute)


@router.get(
    "/memberships/churn",
    response_model=ChurnSummaryResponse,
    responses=ERROR_RESPONSES,
    summary="Compact monthly churn summary (admin dashboard contract)",
)
def churn_summary(
    branch_id: BranchIdParam = None,
    month: Annotated[str | None, Query(description="Month as YYYY-MM (default: current month)")] = None,
    principal: Principal = Depends(require_analytics_access),
    settings: Settings = Depends(get_settings),
    repo: MemberRepository = Depends(get_member_repository),
) -> dict[str, Any]:
    """Returns exactly ``{branchId, month, churnRate, churnedMembers, membersAtStart, renewalRate}``
    with the same formulas as ``/analytics/memberships``. churnRate is 0..1 with 4 decimals."""
    ctx = build_context(principal, settings, branch_id, use_range=False, month=month)
    if month is None:
        month_start = ctx.today.replace(day=1)
    else:
        match = _MONTH.match(month)
        if not match or not 1 <= int(match.group(2)) <= 12 or int(match.group(1)) < 2000:
            raise ValidationFailedError("month: must be formatted as YYYY-MM")
        month_start = date(int(match.group(1)), int(match.group(2)), 1)
        if month_start > ctx.today.replace(day=1):
            raise ValidationFailedError("month: must not be in the future")
    ctx.extra["month"] = month_start.isoformat()

    def compute() -> dict[str, Any]:
        return month_summary(repo.fetch_subscriptions(ctx.scope.branch_ids), month_start, ctx.today, ctx.tz)

    payload = cached_response("memberships-churn", ctx, compute)
    payload.pop("meta")
    return {"branch_id": ctx.scope.requested_branch_id, **payload}
