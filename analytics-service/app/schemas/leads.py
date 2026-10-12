from __future__ import annotations

from pydantic import Field

from app.schemas.common import CamelModel, QueryMeta


class LeadsSummary(CamelModel):
    total_leads: int
    open_leads: int = Field(..., description="Leads not yet CONVERTED or LOST")
    conversions: int
    conversion_rate: float | None = Field(..., description="CONVERTED / totalLeads (0..1)")
    lost_leads: int
    trial_leads: int = Field(..., description="Leads that started a trial subscription")
    trial_conversions: int
    trial_to_paid_rate: float | None = Field(..., description="trialConversions / trialLeads (0..1)")
    median_days_to_convert: float | None
    unassigned_leads: int = Field(..., description="Leads without an owner (not in ownerLeaderboard)")


class FunnelStage(CamelModel):
    status: str
    count: int
    share: float | None = Field(..., description="count / totalLeads (0..1)")


class LeadFunnel(CamelModel):
    total_leads: int
    stages: list[FunnelStage] = Field(
        ..., description="NEW, CONTACTED, TOUR_SCHEDULED, TRIAL_ACTIVE, CONVERTED, LOST (always all six)"
    )


class SourceConversion(CamelModel):
    source: str
    leads: int
    conversions: int
    conversion_rate: float | None


class WeeklyLeads(CamelModel):
    week_start: str = Field(..., description="Monday of the week (ISO date)")
    leads: int = Field(..., description="Leads created in the week")
    conversions: int = Field(..., description="Leads (created in the range) converted in the week")


class LostReasonCount(CamelModel):
    reason: str
    count: int
    share: float | None = Field(..., description="count / LOST leads (0..1)")


class OwnerStats(CamelModel):
    owner_user_id: int
    owner_name: str | None
    leads_handled: int
    conversions: int
    conversion_rate: float | None


class FollowUpSla(CamelModel):
    sla_hours: int
    total_leads: int
    contacted_leads: int = Field(..., description="Leads with at least one CALL/EMAIL/WHATSAPP/SMS/VISIT")
    contacted_within_24h: int = Field(..., alias="contactedWithin24h")
    rate: float | None = Field(..., description="contactedWithin24h / totalLeads (0..1)")
    median_hours_to_first_contact: float | None


class LeadsResponse(CamelModel):
    meta: QueryMeta
    summary: LeadsSummary
    funnel: LeadFunnel
    by_source: list[SourceConversion]
    weekly_trend: list[WeeklyLeads]
    lost_reasons: list[LostReasonCount]
    owner_leaderboard: list[OwnerStats]
    follow_up_sla: FollowUpSla
