from datetime import date

import pytest

from app.services.attendance import compute_attendance, compute_peak_hours, hourly_occupancy, prepare_visits
from tests.helpers import IST, frame, local_to_utc, utc


def _visit(vid, member, check_in, check_out=None, auto=False, tz=IST, branch=1):
    return {
        "id": vid,
        "member_id": member,
        "branch_id": branch,
        "check_in_time": check_in,
        "check_out_time": check_out,
        "auto_checked_out": auto,
        "timezone": tz,
    }


@pytest.fixture
def visits():
    return frame(
        [
            # UTC 1 March 20:00 == local Monday 2 March 01:30 (IST) -> counted on 2 March
            _visit(1, 1, utc("2026-03-01T20:00:00"), utc("2026-03-01T21:00:00")),  # 60 min
            _visit(2, 1, utc("2026-03-03T12:30:00"), utc("2026-03-03T13:15:00")),  # 45 min
            _visit(
                3, 2, utc("2026-03-03T12:45:00"), utc("2026-03-03T18:00:00"), auto=True
            ),  # excluded from duration
            # UTC 8 March 19:00 == local 9 March 00:30 -> outside the range
            _visit(4, 2, utc("2026-03-08T19:00:00"), utc("2026-03-08T20:00:00")),
            _visit(5, 3, utc("2026-03-04T03:30:00"), None),  # still open
        ]
    )


def test_daily_attendance_uses_branch_local_dates(visits):
    out = compute_attendance(visits, date(2026, 3, 2), date(2026, 3, 8), "day")
    by_day = {p["period"]: p for p in out["series"]}
    assert len(out["series"]) == 7
    assert by_day["2026-03-02"] == {
        "period": "2026-03-02",
        "visits": 1,
        "unique_members": 1,
        "avg_duration_minutes": 60.0,
    }
    assert by_day["2026-03-03"]["visits"] == 2
    assert by_day["2026-03-03"]["unique_members"] == 2
    assert by_day["2026-03-03"]["avg_duration_minutes"] == 45.0  # auto check-out ignored
    assert by_day["2026-03-04"]["avg_duration_minutes"] is None  # open visit has no duration
    assert by_day["2026-03-08"]["visits"] == 0

    s = out["summary"]
    assert s["total_visits"] == 4
    assert s["unique_members"] == 3
    assert s["avg_duration_minutes"] == 52.5
    assert s["visits_with_duration"] == 2
    assert s["auto_checked_out_visits"] == 1
    assert s["avg_visits_per_member"] == 1.33


def test_weekly_granularity_and_weekday_breakdown(visits):
    out = compute_attendance(visits, date(2026, 3, 2), date(2026, 3, 8), "week")
    assert out["series"] == [
        {"period": "2026-03-02", "visits": 4, "unique_members": 3, "avg_duration_minutes": 52.5}
    ]
    wd = {w["weekday"]: w for w in out["by_weekday"]}
    assert (wd["MONDAY"]["visits"], wd["TUESDAY"]["visits"], wd["WEDNESDAY"]["visits"]) == (1, 2, 1)
    assert wd["TUESDAY"]["share"] == 0.5
    assert wd["SUNDAY"]["visits"] == 0


def test_empty_input_returns_zero_filled_series():
    out = compute_attendance(frame([]), date(2026, 3, 1), date(2026, 3, 3), "day")
    assert [p["visits"] for p in out["series"]] == [0, 0, 0]
    assert out["summary"]["total_visits"] == 0
    assert out["summary"]["avg_duration_minutes"] is None


@pytest.fixture
def peak_visits():
    return frame(
        [
            _visit(1, 1, local_to_utc("2026-03-02 18:10"), local_to_utc("2026-03-02 19:10")),  # Mon, 60 min
            _visit(2, 2, local_to_utc("2026-03-09 18:20"), local_to_utc("2026-03-09 18:50")),  # Mon, 30 min
            _visit(3, 3, local_to_utc("2026-03-09 18:40"), local_to_utc("2026-03-09 23:00"), auto=True),
            _visit(4, 1, local_to_utc("2026-03-03 07:00"), local_to_utc("2026-03-03 08:00")),  # Tue, 60 min
        ]
    )


def test_peak_hour_heatmap_averages_per_weekday_occurrence(peak_visits):
    out = compute_peak_hours(peak_visits, date(2026, 3, 2), date(2026, 3, 15))
    monday = out["heatmap"][0]
    assert monday["weekday"] == "MONDAY" and monday["occurrences"] == 2
    assert monday["avg_check_ins"][18] == 1.5  # 3 check-ins over 2 Mondays (local hour 18)
    assert out["heatmap"][1]["avg_check_ins"][7] == 0.5
    assert sum(sum(r["avg_check_ins"]) for r in out["heatmap"]) == 2.0
    assert out["peak_windows"] == [
        {"weekday": "MONDAY", "iso_day": 1, "hour": 18, "window": "18:00-19:00", "avg_check_ins": 1.5},
        {"weekday": "TUESDAY", "iso_day": 2, "hour": 7, "window": "07:00-08:00", "avg_check_ins": 0.5},
    ]
    assert out["occupancy"] is None


def test_peak_hours_converts_each_branch_timezone():
    # Same UTC instant, two branches in different timezones -> different local hours.
    v = frame(
        [
            _visit(1, 1, utc("2026-03-02T12:00:00"), utc("2026-03-02T13:00:00"), tz="Asia/Kolkata"),  # 17:30
            _visit(
                2, 2, utc("2026-03-02T12:00:00"), utc("2026-03-02T13:00:00"), tz="Asia/Dubai", branch=2
            ),  # 16:00
        ]
    )
    out = compute_peak_hours(v, date(2026, 3, 2), date(2026, 3, 2))
    monday = out["heatmap"][0]["avg_check_ins"]
    assert monday[17] == 1.0 and monday[16] == 1.0 and monday[12] == 0.0


def test_hourly_occupancy_splits_visits_across_hours(peak_visits):
    prepared = prepare_visits(peak_visits, date(2026, 3, 2), date(2026, 3, 15))
    hours, assumed = hourly_occupancy(prepared, date(2026, 3, 2), date(2026, 3, 15))
    assert assumed == 60.0  # median of genuine durations (60, 30, 60)
    # hour 18: 50 + 30 + 20 minutes; hour 19: 10 + 40 minutes (auto check-out capped at median)
    assert hours[18] == pytest.approx((100 / 60) / 14)
    assert hours[19] == pytest.approx((50 / 60) / 14)
    assert hours[7] == pytest.approx(1 / 14)
    assert hours.sum() == pytest.approx((100 + 50 + 60) / 60 / 14)


def test_peak_hours_occupancy_vs_capacity(peak_visits):
    branch = {"branch_id": 1, "name": "Bengaluru", "capacity": 10}
    occ = compute_peak_hours(peak_visits, date(2026, 3, 2), date(2026, 3, 15), branch)["occupancy"]
    assert occ["peak_hour"] == 18
    assert occ["peak_avg_occupancy"] == 0.12
    assert occ["peak_utilisation_pct"] == 1.19
    assert occ["hours"][19] == {"hour": 19, "avg_occupancy": 0.06, "utilisation_pct": 0.6}
    assert len(occ["hours"]) == 24
