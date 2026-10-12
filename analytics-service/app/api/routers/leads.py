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
from app.repositories.leads import LeadRepository
from app.schemas.leads import LeadsResponse
from app.services.leads import compute_leads

router = APIRouter(prefix="/analytics", tags=["leads"])


def get_lead_repository() -> LeadRepository:
    """One instance per request (the engine/pool is process-wide); overridable in tests."""
    return LeadRepository(get_engine())


@router.get(
    "/leads",
    response_model=LeadsResponse,
    responses=ERROR_RESPONSES,
    summary="Sales pipeline: lead funnel, conversion by source/owner, trials and follow-up SLA",
)
def leads(
    branch_id: BranchIdParam = None,
    date_from: FromParam = None,
    date_to: ToParam = None,
    principal: Principal = Depends(require_analytics_access),
    settings: Settings = Depends(get_settings),
    repo: LeadRepository = Depends(get_lead_repository),
) -> dict[str, Any]:
    """Covers leads created (business-tz date) in the range at branches in scope; statuses are
    current. conversionRate = CONVERTED / leads; trialToPaidRate = CONVERTED / leads with a trial
    subscription; medianDaysToConvert over converted leads; weeklyTrend buckets new leads by
    created_at and their conversions by converted_at (weeks start Monday, empty weeks = 0);
    ownerLeaderboard skips unassigned leads (see summary.unassignedLeads); followUpSla.rate = leads
    whose first CALL/EMAIL/WHATSAPP/SMS/VISIT activity came within 24h of creation / all leads.
    Rates are fractions 0..1 (null when the denominator is 0)."""
    ctx = build_context(principal, settings, branch_id, date_from, date_to)

    def compute() -> dict[str, Any]:
        b = ctx.scope.branch_ids
        window = utc_window(ctx.date_from, ctx.date_to)
        return compute_leads(
            repo.fetch_leads(*window, b),
            repo.fetch_first_activities(*window, b),
            ctx.date_from,
            ctx.date_to,
            ctx.tz,
        )

    return cached_response("leads", ctx, compute)
