from datetime import date

import pandas as pd
import pytest

from app.services.classes import compute_classes
from tests.helpers import IST, frame, local_to_utc, utc

FROM, TO = date(2026, 3, 2), date(2026, 3, 8)  # Mon..Sun

CLASS_TYPES = frame(
    [
        {"class_type_id": 1, "name": "Sunrise Yoga", "category": "YOGA"},
        {"class_type_id": 2, "name": "HIIT Blast", "category": "HIIT"},
    ]
)
INSTRUCTORS = frame(
    [
        {"trainer_id": 7, "full_name": "Tara Coach"},
        {"trainer_id": 8, "full_name": "Vik Coach"},
    ]
)


def _session(sid, type_id, instructor, start, capacity, status="COMPLETED"):
    starts = local_to_utc(start) if isinstance(start, str) else start
    return {
        "session_id": sid,
        "class_type_id": type_id,
        "branch_id": 1,
        "instructor_id": instructor,
        "starts_at": starts,
        "ends_at": starts + pd.Timedelta(hours=1),
        "capacity": capacity,
        "status": status,
    }


_next_booking = iter(range(1, 10_000))


def _bookings(session_id, *statuses, promoted=()):
    """One booking per status; indexes listed in ``promoted`` get a promoted_at timestamp."""
    return [
        {
            "booking_id": next(_next_booking),
            "session_id": session_id,
            "status": status,
            "promoted_at": utc("2026-03-01T00:00:00") if i in promoted else None,
        }
        for i, status in enumerate(statuses)
    ]


@pytest.fixture
def inputs():
    sessions = frame(
        [
            # Mon 07:00 yoga by 7, cap 4: full (3 attended + 1 no-show), 1 waitlisted, 1 late cancel
            _session(1, 1, 7, "2026-03-02 07:00", 4),
            # Wed 18:00 HIIT by 7, cap 10: 2 attended + 1 booked (one promoted from waitlist), 1 cancel
            _session(2, 2, 7, "2026-03-04 18:00", 10),
            # Wed 18:00 yoga by 8, cap 5: 1 no-show
            _session(3, 1, 8, "2026-03-04 18:30", 5),
            # cancelled session: bookings must not count anywhere
            _session(4, 2, 8, "2026-03-05 09:00", 10, status="CANCELLED"),
            # local 9 Mar -> outside range
            _session(5, 2, 8, "2026-03-09 00:30", 10),
        ]
    )
    bookings = frame(
        _bookings(1, "ATTENDED", "ATTENDED", "ATTENDED", "NO_SHOW", "WAITLISTED", "LATE_CANCELLED")
        + _bookings(2, "ATTENDED", "ATTENDED", "BOOKED", "CANCELLED", promoted=(2,))
        + _bookings(3, "NO_SHOW")
        + _bookings(4, "BOOKED", "LATE_CANCELLED", "WAITLISTED")
        + _bookings(5, "ATTENDED", "ATTENDED")
    )
    return CLASS_TYPES, sessions, bookings, INSTRUCTORS


def test_summary_rates_and_counts(inputs):
    s = compute_classes(*inputs, FROM, TO, IST)["summary"]
    assert s["sessions"] == 3 and s["cancelled_sessions"] == 1
    assert s["capacity"] == 19 and s["occupied"] == 8
    assert s["fill_rate"] == round(8 / 19, 4)
    assert (s["attended"], s["no_shows"]) == (5, 2)
    assert s["attendance_rate"] == round(5 / 7, 4)
    # non-waitlisted bookings on active sessions: 5 (s1) + 4 (s2) + 1 (s3) = 10
    assert s["late_cancels"] == 1 and s["late_cancel_rate"] == 0.1
    assert s["waitlist_joins"] == 2 and s["waitlist_promotions"] == 1
    assert s["sessions_full"] == 1
    assert s["avg_occupied_per_session"] == 2.67


def test_cancelled_sessions_excluded_from_metrics(inputs):
    class_types, sessions, bookings, instructors = inputs
    only_cancelled = sessions[sessions["session_id"] == 4]
    out = compute_classes(class_types, only_cancelled, bookings, instructors, FROM, TO, IST)
    s = out["summary"]
    assert s["cancelled_sessions"] == 1 and s["sessions"] == 0
    assert s["occupied"] == 0 and s["late_cancels"] == 0 and s["waitlist_joins"] == 0
    assert s["fill_rate"] is None and s["late_cancel_rate"] is None
    assert out["by_class_type"] == [] and out["instructors"] == [] and out["time_slots"] == []


def test_by_class_type_sorted_by_occupied(inputs):
    by_type = compute_classes(*inputs, FROM, TO, IST)["by_class_type"]
    assert [t["class_type_id"] for t in by_type] == [1, 2]
    yoga, hiit = by_type
    assert yoga["name"] == "Sunrise Yoga" and yoga["category"] == "YOGA"
    assert (yoga["sessions"], yoga["capacity"], yoga["occupied"]) == (2, 9, 5)
    assert yoga["fill_rate"] == round(5 / 9, 4)
    assert yoga["attendance_rate"] == 0.6  # 3 / (3 + 2)
    assert yoga["late_cancels"] == 1 and yoga["late_cancel_rate"] == round(1 / 6, 4)
    assert yoga["waitlist_joins"] == 1
    assert (hiit["sessions"], hiit["occupied"], hiit["fill_rate"]) == (1, 3, 0.3)
    assert hiit["attendance_rate"] == 1.0 and hiit["late_cancel_rate"] == 0.0
    assert hiit["waitlist_joins"] == 1


def test_instructor_stats(inputs):
    t7, t8 = compute_classes(*inputs, FROM, TO, IST)["instructors"]
    assert t7["trainer_id"] == 7 and t7["name"] == "Tara Coach"
    assert (t7["sessions_taught"], t7["occupied"], t7["attended"], t7["no_shows"]) == (2, 7, 5, 1)
    assert t7["avg_fill_rate"] == 0.65  # mean(4/4, 3/10)
    assert t7["attendance_rate"] == round(5 / 6, 4)
    assert t8["trainer_id"] == 8 and t8["sessions_taught"] == 1
    assert t8["avg_fill_rate"] == 0.2 and t8["attendance_rate"] == 0.0


def test_time_slots_use_business_timezone():
    sessions = frame(
        [
            _session(1, 1, 7, utc("2026-03-02T01:00:00"), 10),  # Mon 06:30 IST
            _session(2, 1, 7, utc("2026-03-04T12:45:00"), 10),  # Wed 18:15 IST
            _session(3, 1, 7, utc("2026-03-04T13:00:00"), 10),  # Wed 18:30 IST
            _session(4, 1, 7, utc("2026-03-07T20:00:00"), 10),  # Sun 01:30 IST (Sat in UTC)
        ]
    )
    bookings = frame(_bookings(2, "BOOKED", "WAITLISTED") + _bookings(3, "ATTENDED"))
    out = compute_classes(CLASS_TYPES, sessions, bookings, INSTRUCTORS, FROM, TO, IST)
    slots = [
        (t["weekday"], t["hour"], t["sessions"], t["occupied"], t["capacity"]) for t in out["time_slots"]
    ]
    assert slots == [(1, 6, 1, 0, 10), (3, 18, 2, 2, 20), (7, 1, 1, 0, 10)]
    assert out["time_slots"][1]["fill_rate"] == 0.1 and out["time_slots"][1]["waitlist_joins"] == 1
    assert out["time_slots"][0]["fill_rate"] == 0.0

    # same sessions in UTC: the 01:00Z session is hour 1 and the 20:00Z one is Saturday
    utc_slots = compute_classes(CLASS_TYPES, sessions, bookings, INSTRUCTORS, FROM, TO, "UTC")["time_slots"]
    assert [(t["weekday"], t["hour"]) for t in utc_slots] == [(1, 1), (3, 12), (3, 13), (6, 20)]


def test_daily_zero_fill(inputs):
    daily = compute_classes(*inputs, FROM, TO, IST)["daily"]
    assert [d["date"] for d in daily] == [date(2026, 3, d) for d in range(2, 9)]
    by_date = {d["date"]: d for d in daily}
    mon, wed, thu = by_date[date(2026, 3, 2)], by_date[date(2026, 3, 4)], by_date[date(2026, 3, 5)]
    assert (mon["sessions"], mon["occupied"], mon["capacity"], mon["attended"], mon["fill_rate"]) == (
        1,
        4,
        4,
        3,
        1.0,
    )
    assert (wed["sessions"], wed["occupied"], wed["capacity"], wed["attended"]) == (2, 4, 15, 2)
    assert wed["fill_rate"] == round(4 / 15, 4)
    # the cancelled session's day and empty days are zero
    assert thu == {
        "date": date(2026, 3, 5),
        "sessions": 0,
        "occupied": 0,
        "capacity": 0,
        "attended": 0,
        "fill_rate": None,
    }


def test_empty_input():
    out = compute_classes(frame([]), frame([]), frame([]), frame([]), FROM, TO, IST)
    s = out["summary"]
    assert s == {
        "sessions": 0,
        "cancelled_sessions": 0,
        "capacity": 0,
        "occupied": 0,
        "fill_rate": None,
        "attended": 0,
        "no_shows": 0,
        "attendance_rate": None,
        "late_cancels": 0,
        "late_cancel_rate": None,
        "waitlist_joins": 0,
        "waitlist_promotions": 0,
        "sessions_full": 0,
        "avg_occupied_per_session": None,
    }
    assert out["by_class_type"] == [] and out["time_slots"] == [] and out["instructors"] == []
    assert len(out["daily"]) == 7 and all(d["sessions"] == 0 and d["fill_rate"] is None for d in out["daily"])


def test_none_inputs_and_sessions_without_bookings():
    sessions = frame([_session(1, 99, 42, "2026-03-03 10:00", 12)])
    out = compute_classes(None, sessions, None, None, FROM, TO, IST)
    assert out["summary"]["sessions"] == 1 and out["summary"]["fill_rate"] == 0.0
    (t,) = out["by_class_type"]
    assert t["class_type_id"] == 99 and t["name"] is None and t["category"] is None
    (i,) = out["instructors"]
    assert i["trainer_id"] == 42 and i["name"] is None and i["avg_fill_rate"] == 0.0
    assert i["attendance_rate"] is None
