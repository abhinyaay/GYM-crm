from __future__ import annotations

from pydantic import Field

from app.schemas.common import CamelModel, QueryMeta


class MembershipMonth(CamelModel):
    month: str
    ended_subscriptions: int
    renewed_subscriptions: int
    renewal_rate: float | None
    members_at_start: int
    churned_members: int
    pending_members: int = Field(..., description="Access ended but grace window still open")
    churn_rate: float
    new_subscriptions: int
    subscriptions_frozen: int
    frozen_days: int


class PlanMix(CamelModel):
    plan_id: int
    plan_name: str | None
    active_subscriptions: int
    share: float | None


class ExpiryWindow(CamelModel):
    within_days: int
    expiring: int
    with_renewal: int
    without_renewal: int


class FreezeUsage(CamelModel):
    currently_frozen: int
    active_subscriptions_with_freeze_used: int
    share_with_freeze_used: float | None
    avg_freeze_days_used: float | None


class CancellationsByPlan(CamelModel):
    plan_id: int
    plan_name: str | None
    cancellations: int
    after_activation: int


class MembershipSummary(CamelModel):
    active_subscriptions: int
    ended_subscriptions: int
    renewed_subscriptions: int
    renewal_rate: float | None
    avg_monthly_churn_rate: float | None
    cancellations: int


class MembershipsResponse(CamelModel):
    meta: QueryMeta
    summary: MembershipSummary
    monthly: list[MembershipMonth]
    plan_mix: list[PlanMix]
    upcoming_expiries: list[ExpiryWindow]
    freeze_usage: FreezeUsage
    cancellations_by_plan: list[CancellationsByPlan]


class ChurnSummaryResponse(CamelModel):
    """Compact churn summary consumed by the Java admin dashboard. Field names are a contract."""

    branch_id: int | None
    month: str = Field(..., pattern=r"^\d{4}-\d{2}$")
    churn_rate: float = Field(..., ge=0, le=1)
    churned_members: int
    members_at_start: int
    renewal_rate: float | None
