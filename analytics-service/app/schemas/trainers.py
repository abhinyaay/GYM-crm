from __future__ import annotations

from app.schemas.common import CamelModel, QueryMeta


class TrainerStats(CamelModel):
    trainer_id: int
    name: str | None
    employee_code: str | None
    branch_id: int | None
    branch_name: str | None
    status: str | None
    active_clients: int
    max_active_clients: int | None
    sessions_scheduled: int
    sessions_completed: int
    sessions_cancelled: int
    sessions_no_show: int
    completion_rate: float | None
    no_show_rate: float | None
    booked_hours: float | None
    available_hours: float | None
    utilisation_pct: float | None
    avg_client_visits: float | None


class TrainerSummary(CamelModel):
    trainers: int
    active_clients: int
    sessions_completed: int
    sessions_no_show: int
    booked_hours: float | None
    available_hours: float | None
    utilisation_pct: float | None


class TrainersResponse(CamelModel):
    meta: QueryMeta
    summary: TrainerSummary
    trainers: list[TrainerStats]
