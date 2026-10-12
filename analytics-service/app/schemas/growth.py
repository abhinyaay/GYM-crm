from __future__ import annotations

from pydantic import Field

from app.schemas.common import CamelModel, QueryMeta


class ReferralSummary(CamelModel):
    total: int = Field(..., description="Referrals created in the range")
    by_status: dict[str, int] = Field(
        ..., description="PENDING, CONVERTED, REVERSED, REJECTED (always all four)"
    )
    conversion_rate: float | None = Field(..., description="(CONVERTED + REVERSED) / (total - REJECTED)")
    reversal_rate: float | None = Field(..., description="REVERSED / (CONVERTED + REVERSED)")
    capped: int = Field(..., description="Conversions that earned the referrer nothing (monthly cap)")
    median_days_to_convert: float | None


class ChannelStats(CamelModel):
    channel: str
    referrals: int
    conversions: int
    conversion_rate: float | None


class RewardSummary(CamelModel):
    credit_issued: float | None = Field(..., description="Store credit paid out (rewards AVAILABLE)")
    credit_pending: float | None = Field(..., description="Store credit still in the hold period")
    credit_reversed: float | None
    codes_issued: int = Field(..., description="Welcome discount codes issued")
    rewards_reversed: int


class CampaignTotals(CamelModel):
    messages: int
    conversions: int
    conversion_rate: float | None
    revenue: float | None = Field(..., description="Invoices attributed to campaign messages (gross, INR)")
    revenue_per_message: float | None


class CampaignStats(CamelModel):
    campaign_id: int
    name: str
    segment: str
    messages: int
    members: int
    conversions: int
    conversion_rate: float | None
    revenue: float | None
    offers_issued: int
    offers_redeemed: int
    redemption_rate: float | None


class SegmentStats(CamelModel):
    segment: str
    messages: int
    members: int
    conversions: int
    conversion_rate: float | None
    revenue: float | None
    offers_issued: int
    offers_redeemed: int
    redemption_rate: float | None


class GrowthResponse(CamelModel):
    meta: QueryMeta
    referrals: ReferralSummary
    by_channel: list[ChannelStats]
    rewards: RewardSummary
    campaigns: CampaignTotals
    campaign_performance: list[CampaignStats]
    by_segment: list[SegmentStats]
