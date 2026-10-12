from __future__ import annotations

from pydantic import Field

from app.schemas.common import CamelModel, QueryMeta


class RegistrationPeriod(CamelModel):
    period: str
    new_members: int


class MemberSummary(CamelModel):
    total_members: int
    new_members: int
    active_members: int
    active_by_status: dict[str, int]
    members_without_access: int


class EngagementSegment(CamelModel):
    segment: str
    min_visits: int
    members: int
    share: float | None


class RetentionCell(CamelModel):
    month_offset: int
    retained_members: int
    rate: float | None


class Cohort(CamelModel):
    cohort: str = Field(..., description="Registration month YYYY-MM")
    members: int
    retention: list[RetentionCell]


class MembersResponse(CamelModel):
    meta: QueryMeta
    summary: MemberSummary
    series: list[RegistrationPeriod]
    engagement: list[EngagementSegment]
    cohorts: list[Cohort]


class ChurnReason(CamelModel):
    code: str
    points: int
    description: str


class ChurnRiskMember(CamelModel):
    member_id: int
    member_code: str | None
    name: str | None
    branch_id: int | None
    branch_name: str | None
    subscription_id: int
    subscription_status: str
    plan_name: str | None
    subscription_end_date: str
    last_visit: str | None
    days_since_last_visit: int
    visits_last_30_days: int
    score: int = Field(..., ge=0, le=100)
    band: str
    reasons: list[ChurnReason]


class ChurnRuleInfo(CamelModel):
    code: str
    weight: int
    description: str


class ChurnRiskResponse(CamelModel):
    meta: QueryMeta
    items: list[ChurnRiskMember]
    page: int
    size: int
    total_items: int
    total_pages: int
    band_counts: dict[str, int]
    scored_members: int
    rules: list[ChurnRuleInfo]
