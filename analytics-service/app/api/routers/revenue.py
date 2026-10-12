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
    get_revenue_repository,
)
from app.core.config import Settings, get_settings
from app.core.security import Principal, require_analytics_access
from app.repositories.base import utc_window
from app.repositories.revenue import RevenueRepository
from app.schemas.revenue import RevenueResponse
from app.services.common import Granularity
from app.services.revenue import compute_revenue

router = APIRouter(prefix="/analytics", tags=["revenue"])


@router.get(
    "/revenue",
    response_model=RevenueResponse,
    responses=ERROR_RESPONSES,
    summary="Gross, refunds and net revenue; by plan, branch and payment method",
)
def revenue(
    branch_id: BranchIdParam = None,
    date_from: FromParam = None,
    date_to: ToParam = None,
    granularity: GranularityParam = Granularity.day,
    principal: Principal = Depends(require_analytics_access),
    settings: Settings = Depends(get_settings),
    repo: RevenueRepository = Depends(get_revenue_repository),
) -> dict[str, Any]:
    """Cash-basis revenue in the business timezone. Gross = successful payments (SUCCEEDED,
    PARTIALLY_REFUNDED, REFUNDED) by processed_at; refunds = SUCCEEDED refunds by their processed_at;
    net = gross - refunds. Failed-payment rate = FAILED / all attempts. Average revenue per paying
    member = net / distinct members with a successful payment."""
    ctx = build_context(principal, settings, branch_id, date_from, date_to, granularity)

    def compute() -> dict[str, Any]:
        window = utc_window(ctx.date_from, ctx.date_to)
        payments = repo.fetch_payments(*window, ctx.scope.branch_ids)
        refunds = repo.fetch_refunds(*window, ctx.scope.branch_ids)
        return compute_revenue(payments, refunds, ctx.date_from, ctx.date_to, ctx.granularity, ctx.tz)

    return cached_response("revenue", ctx, compute)
