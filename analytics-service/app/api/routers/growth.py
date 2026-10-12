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
from app.repositories.growth import GrowthRepository
from app.schemas.growth import GrowthResponse
from app.services.growth import compute_growth

router = APIRouter(prefix="/analytics", tags=["growth"])


def get_growth_repository() -> GrowthRepository:
    """One instance per request (the engine/pool is process-wide); overridable in tests."""
    return GrowthRepository(get_engine())


@router.get(
    "/growth",
    response_model=GrowthResponse,
    responses=ERROR_RESPONSES,
    summary="Referral programme funnel and rewards, retention-campaign conversions and revenue",
)
def growth(
    branch_id: BranchIdParam = None,
    date_from: FromParam = None,
    date_to: ToParam = None,
    principal: Principal = Depends(require_analytics_access),
    settings: Settings = Depends(get_settings),
    repo: GrowthRepository = Depends(get_growth_repository),
) -> dict[str, Any]:
    """Referrals created (business-tz date) in the range at branches in scope (the referee's
    branch), with their current status and rewards; campaign messages sent in the range to members
    of branches in scope, with the conversions the core attributed to them. See
    ``app/services/growth.py`` for every formula. Rates are fractions 0..1 (null when the
    denominator is 0)."""
    ctx = build_context(principal, settings, branch_id, date_from, date_to)

    def compute() -> dict[str, Any]:
        b = ctx.scope.branch_ids
        window = utc_window(ctx.date_from, ctx.date_to)
        return compute_growth(
            repo.fetch_referrals(*window, b),
            repo.fetch_rewards(*window, b),
            repo.fetch_recipients(*window, b),
            ctx.date_from,
            ctx.date_to,
            ctx.tz,
        )

    return cached_response("growth", ctx, compute)
