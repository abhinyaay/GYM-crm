from __future__ import annotations

from pydantic import Field

from app.schemas.common import CamelModel, QueryMeta


class AttendancePeriod(CamelModel):
    period: str = Field(..., description="Start date of the period (ISO date)")
    visits: int
    unique_members: int
    avg_duration_minutes: float | None


class WeekdayVisits(CamelModel):
    weekday: str
    iso_day: int
    visits: int
    avg_visits_per_day: float | None
    share: float | None


class AttendanceSummary(CamelModel):
    total_visits: int
    unique_members: int
    avg_visits_per_member: float | None
    avg_duration_minutes: float | None
    visits_with_duration: int
    auto_checked_out_visits: int
    avg_visits_per_day: float | None


class AttendanceResponse(CamelModel):
    meta: QueryMeta
    summary: AttendanceSummary
    series: list[AttendancePeriod]
    by_weekday: list[WeekdayVisits]


class HeatmapRow(CamelModel):
    weekday: str
    iso_day: int
    occurrences: int = Field(..., description="Number of such weekdays in the range")
    total_check_ins: int
    avg_check_ins: list[float | None] = Field(..., description="24 values, index = local hour 0..23")


class PeakWindow(CamelModel):
    weekday: str
    iso_day: int
    hour: int
    window: str
    avg_check_ins: float | None


class OccupancyHour(CamelModel):
    hour: int
    avg_occupancy: float | None
    utilisation_pct: float | None


class Occupancy(CamelModel):
    branch_id: int | None
    branch_name: str | None
    capacity: int | None
    assumed_visit_minutes: float | None
    hours: list[OccupancyHour]
    peak_hour: int | None
    peak_avg_occupancy: float | None
    peak_utilisation_pct: float | None


class PeakHoursResponse(CamelModel):
    meta: QueryMeta
    total_check_ins: int
    heatmap: list[HeatmapRow]
    peak_windows: list[PeakWindow]
    occupancy: Occupancy | None = Field(None, description="Only when a single branch is selected")
