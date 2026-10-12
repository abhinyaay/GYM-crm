from datetime import date

import pytest

from app.services.trainers import available_hours, compute_trainers
from tests.helpers import IST, frame, local_to_utc

FROM, TO = date(2026, 3, 2), date(2026, 3, 15)  # two full weeks


def _session(sid, trainer, start, end, status):
    return {
        "session_id": sid,
        "trainer_id": trainer,
        "member_id": 1,
        "branch_id": 1,
        "starts_at": local_to_utc(start),
        "ends_at": local_to_utc(end),
        "status": status,
    }


@pytest.fixture
def inputs():
    trainers = frame(
        [
            {
                "trainer_id": 1,
                "full_name": "Asha Trainer",
                "employee_code": "T1",
                "home_branch_id": 1,
                "branch_name": "Branch 1",
                "status": "ACTIVE",
                "max_active_clients": 20,
            },
            {
                "trainer_id": 2,
                "full_name": "Bala Trainer",
                "employee_code": "T2",
                "home_branch_id": 1,
                "branch_name": "Branch 1",
                "status": "ACTIVE",
                "max_active_clients": 20,
            },
        ]
    )
    availability = frame(
        [
            {
                "trainer_id": 1,
                "branch_id": 1,
                "day_of_week": 1,
                "start_time": "06:00:00",
                "end_time": "10:00:00",
            },
            {
                "trainer_id": 1,
                "branch_id": 1,
                "day_of_week": 3,
                "start_time": "17:00:00",
                "end_time": "21:00:00",
            },
        ]
    )
    sessions = frame(
        [
            _session(1, 1, "2026-03-02 07:00", "2026-03-02 08:00", "COMPLETED"),
            _session(2, 1, "2026-03-04 18:00", "2026-03-04 19:30", "COMPLETED"),
            _session(3, 1, "2026-03-11 18:00", "2026-03-11 19:00", "SCHEDULED"),
            _session(4, 1, "2026-03-09 07:00", "2026-03-09 08:00", "CANCELLED"),
            _session(5, 1, "2026-03-09 08:00", "2026-03-09 09:00", "NO_SHOW"),
            _session(
                6, 1, "2026-03-16 00:30", "2026-03-16 01:30", "COMPLETED"
            ),  # local 16 Mar -> outside range
        ]
    )
    assignments = frame(
        [
            {"trainer_id": 1, "member_id": 10, "assigned_at": None},
            {"trainer_id": 1, "member_id": 11, "assigned_at": None},
        ]
    )
    client_visits = frame(
        [
            {"member_id": 10, "check_in_time": local_to_utc("2026-03-02 07:00")},
            {"member_id": 10, "check_in_time": local_to_utc("2026-03-05 07:00")},
            {"member_id": 10, "check_in_time": local_to_utc("2026-03-09 07:00")},
            {"member_id": 11, "check_in_time": local_to_utc("2026-03-10 07:00")},
            {"member_id": 11, "check_in_time": local_to_utc("2026-03-20 07:00")},  # outside range
        ]
    )
    return trainers, availability, sessions, assignments, client_visits


def test_available_hours_expands_weekly_slots(inputs):
    _, availability, *_ = inputs
    assert available_hours(availability, FROM, TO).to_dict() == {1: 16.0}
    # a single Monday only
    assert available_hours(availability, date(2026, 3, 2), date(2026, 3, 2)).to_dict() == {1: 4.0}


def test_trainer_utilisation_and_rates(inputs):
    out = compute_trainers(*inputs, FROM, TO, IST)
    t1, t2 = out["trainers"]
    assert t1["name"] == "Asha Trainer"
    assert (
        t1["sessions_scheduled"],
        t1["sessions_completed"],
        t1["sessions_cancelled"],
        t1["sessions_no_show"],
    ) == (1, 2, 1, 1)
    assert t1["booked_hours"] == 3.5
    assert t1["available_hours"] == 16.0
    assert t1["utilisation_pct"] == 21.88
    assert t1["completion_rate"] == 0.5
    assert t1["no_show_rate"] == 0.25
    assert t1["active_clients"] == 2
    assert t1["avg_client_visits"] == 2.0

    assert t2["available_hours"] == 0.0 and t2["utilisation_pct"] is None
    assert t2["completion_rate"] is None and t2["active_clients"] == 0

    assert out["summary"]["utilisation_pct"] == 21.88
    assert out["summary"]["trainers"] == 2
