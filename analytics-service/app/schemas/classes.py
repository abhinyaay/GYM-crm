from __future__ import annotations

from datetime import date

from app.schemas.common import CamelModel, QueryMeta


class ClassesSummary(CamelModel):
    sessions: int
    cancelled_sessions: int
    capacity: int
    occupied: int
    fill_rate: float | None
    attended: int
    no_shows: int
    attendance_rate: float | None
    late_cancels: int
    late_cancel_rate: float | None
    waitlist_joins: int
    waitlist_promotions: int
    sessions_full: int
    avg_occupied_per_session: float | None


class ClassTypeStats(CamelModel):
    class_type_id: int
    name: str | None
    category: str | None
    sessions: int
    capacity: int
    occupied: int
    fill_rate: float | None
    attended: int
    no_shows: int
    attendance_rate: float | None
    late_cancels: int
    late_cancel_rate: float | None
    waitlist_joins: int


class TimeSlotStats(CamelModel):
    weekday: int
    hour: int
    sessions: int
    occupied: int
    capacity: int
    fill_rate: float | None
    waitlist_joins: int


class InstructorStats(CamelModel):
    trainer_id: int
    name: str | None
    sessions_taught: int
    occupied: int
    avg_fill_rate: float | None
    attended: int
    no_shows: int
    attendance_rate: float | None


class DailyClassStats(CamelModel):
    date: date
    sessions: int
    occupied: int
    capacity: int
    attended: int
    fill_rate: float | None


class ClassesResponse(CamelModel):
    meta: QueryMeta
    summary: ClassesSummary
    by_class_type: list[ClassTypeStats]
    time_slots: list[TimeSlotStats]
    instructors: list[InstructorStats]
    daily: list[DailyClassStats]
