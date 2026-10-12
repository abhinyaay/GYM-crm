from __future__ import annotations

from app.schemas.common import CamelModel, QueryMeta


class BranchStats(CamelModel):
    branch_id: int
    code: str | None
    name: str | None
    status: str | None
    capacity: int | None
    active_members: int
    new_members: int
    visits: int
    visits_per_active_member: float | None
    net_revenue: float | None
    revenue_per_active_member: float | None
    peak_avg_occupancy: float | None
    peak_occupancy_pct: float | None
    open_complaints: int
    revenue_rank: int


class BranchSummary(CamelModel):
    branches: int
    active_members: int
    visits: int
    net_revenue: float | None
    open_complaints: int


class BranchesResponse(CamelModel):
    meta: QueryMeta
    summary: BranchSummary
    branches: list[BranchStats]
