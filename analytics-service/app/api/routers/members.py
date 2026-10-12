from __future__ import annotations

from datetime import date, datetime, time, timedelta, tzinfo
from typing import Annotated, Any, Literal

from fastapi import APIRouter, Depends, Query

from app.api.deps import (
    ERROR_RESPONSES,
    BranchIdParam,
    FromParam,
    GranularityParam,
    ToParam,
    build_context,
    cached_response,
    get_member_repository,
    now_utc,
)
from app.core.cache import get_cache
from app.core.config import Settings, get_settings
from app.core.security import Principal, require_analytics_access
from app.repositories.members import MemberRepository
from app.schemas.members import ChurnRiskResponse, MembersResponse
from app.services import churn_risk
from app.services.common import Granularity, json_safe
from app.services.members import ENGAGEMENT_WINDOW_DAYS, compute_members

router = APIRouter(prefix="/analytics", tags=["members"])


def _local_midnight(day: date, tz: tzinfo) -> datetime:
    return datetime.combine(day, time.min, tzinfo=tz)


@router.get(
    "/members",
    response_model=MembersResponse,
    responses=ERROR_RESPONSES,
    summary="Registrations, active members, engagement segments and retention cohorts",
)
def members(
    branch_id: BranchIdParam = None,
    date_from: FromParam = None,
    date_to: ToParam = None,
    granularity: GranularityParam = Granularity.day,
    cohort_months: Annotated[
        int, Query(alias="cohortMonths", ge=1, le=24, description="Number of monthly registration cohorts")
    ] = 12,
    principal: Principal = Depends(require_analytics_access),
    settings: Settings = Depends(get_settings),
    repo: MemberRepository = Depends(get_member_repository),
) -> dict[str, Any]:
    """Members are attributed to their home branch. Active member = holds an ACTIVE/FROZEN/GRACE
    subscription with access today. Engagement segments (members with access, check-ins in the last
    30 days): HIGHLY_ENGAGED >= 12, REGULAR 4-11, LOW 1-3, INACTIVE 0. Retention: share of each
    monthly registration cohort holding a committed subscription in each month since joining."""
    ctx = build_context(
        principal, settings, branch_id, date_from, date_to, granularity, cohort_months=cohort_months
    )

    def compute() -> dict[str, Any]:
        since = _local_midnight(ctx.today - timedelta(days=ENGAGEMENT_WINDOW_DAYS + 1), settings.tz)
        return compute_members(
            repo.fetch_members(ctx.scope.branch_ids),
            repo.fetch_subscriptions(ctx.scope.branch_ids),
            repo.fetch_member_visits(since, ctx.scope.branch_ids),
            ctx.date_from,
            ctx.date_to,
            ctx.granularity,
            ctx.today,
            ctx.tz,
            cohort_months,
        )

    return cached_response("members", ctx, compute)


@router.get(
    "/members/churn-risk",
    response_model=ChurnRiskResponse,
    responses=ERROR_RESPONSES,
    summary="Transparent rule-based churn-risk scores (no ML)",
)
def churn_risk_scores(
    branch_id: BranchIdParam = None,
    min_band: Annotated[
        Literal["LOW", "MEDIUM", "HIGH"],
        Query(alias="minBand", description="Only return members at or above this band"),
    ] = "LOW",
    page: Annotated[int, Query(ge=0, le=100000, description="0-based page index")] = 0,
    size: Annotated[int, Query(ge=1, le=200, description="Page size (max 200)")] = 50,
    principal: Principal = Depends(require_analytics_access),
    settings: Settings = Depends(get_settings),
    repo: MemberRepository = Depends(get_member_repository),
) -> dict[str, Any]:
    """Scores every member currently holding an ACTIVE or GRACE subscription. The score (0-100) is
    the capped sum of the weights of the rules that fire; see ``rules`` in the response and
    ``app/services/churn_risk.py`` for the single source of weights. Bands: HIGH >= 60,
    MEDIUM >= 30, LOW otherwise. Sorted by score, then days since last visit."""
    ctx = build_context(principal, settings, branch_id, use_range=False)

    def scored():
        b = ctx.scope.branch_ids
        lookback = (
            max(
                churn_risk.VISIT_HISTORY_DAYS,
                churn_risk.COMPLAINT_LOOKBACK_DAYS,
                churn_risk.FAILED_PAYMENT_LOOKBACK_DAYS,
            )
            + 1
        )
        since = _local_midnight(ctx.today - timedelta(days=lookback), settings.tz)
        return churn_risk.score_members(
            repo.fetch_members(b),
            repo.fetch_subscriptions(b),
            repo.fetch_member_visits(since, b),
            repo.fetch_last_visits(b),
            repo.fetch_failed_payments(since, b),
            repo.fetch_open_member_complaints(since, b),
            ctx.today,
            ctx.tz,
        )

    # Cache the full scored list once per scope; pagination / band filtering is cheap.
    entry = get_cache().get_or_compute(
        "churn-risk", ctx.cache_key(), lambda: {"generated_at": now_utc(), "scored": scored()}
    )
    page_data = churn_risk.paginate(entry["scored"], min_band, page, size)
    return {
        "meta": ctx.meta(entry["generated_at"]),
        **json_safe(page_data),
        "rules": churn_risk.rules_catalogue(),
    }
