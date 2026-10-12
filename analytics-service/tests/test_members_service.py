from datetime import date, timedelta

import pytest

from app.services.members import compute_members, engagement_segment
from tests.helpers import IST, frame, member, sub, utc

TODAY = date(2026, 3, 15)


@pytest.fixture
def data():
    members = frame(
        [
            member(1, utc("2026-01-10T05:00:00")),
            member(2, utc("2026-01-20T05:00:00")),
            member(3, utc("2026-02-05T05:00:00")),
            member(4, utc("2026-02-28T20:00:00")),  # 1 March 01:30 IST -> March cohort
        ]
    )
    subs = frame(
        [
            sub(1, 1, "EXPIRED", date(2026, 1, 10), date(2026, 2, 9)),
            sub(2, 1, "ACTIVE", date(2026, 2, 10), date(2026, 4, 9), prev=1, change_type="RENEWAL"),
            sub(
                3,
                2,
                "CANCELLED",
                date(2026, 1, 20),
                date(2026, 4, 19),
                cancelled_at=utc("2026-01-31T06:00:00"),
            ),
            sub(4, 3, "GRACE", date(2026, 2, 5), date(2026, 3, 4), grace=7),
            sub(5, 4, "PENDING_PAYMENT", date(2026, 3, 3), date(2026, 4, 2), activated=False),
        ]
    )
    visits = [{"member_id": 1, "check_in_time": utc(f"2026-03-{d:02d}T05:00:00")} for d in range(1, 13)]
    visits += [{"member_id": 1, "check_in_time": utc("2026-02-01T05:00:00")}]  # outside the 30-day window
    visits += [
        {"member_id": 3, "check_in_time": utc("2026-03-01T05:00:00")},
        {"member_id": 3, "check_in_time": utc("2026-03-02T05:00:00")},
        {"member_id": 2, "check_in_time": utc("2026-03-02T05:00:00")},
    ]  # no access -> not segmented
    return members, subs, frame(visits)


def test_registrations_and_active_members(data):
    members, subs, visits = data
    out = compute_members(
        members, subs, visits, date(2026, 1, 1), TODAY, "month", TODAY, IST, cohort_months=3
    )
    assert out["series"] == [
        {"period": "2026-01-01", "new_members": 2},
        {"period": "2026-02-01", "new_members": 1},
        {"period": "2026-03-01", "new_members": 1},
    ]
    s = out["summary"]
    assert s["active_members"] == 2  # ACTIVE (member 1) + GRACE (member 3)
    assert s["active_by_status"] == {"ACTIVE": 1, "FROZEN": 0, "GRACE": 1}
    assert s["total_members"] == 4 and s["members_without_access"] == 2


def test_engagement_segments_only_members_with_access(data):
    members, subs, visits = data
    out = compute_members(
        members, subs, visits, date(2026, 1, 1), TODAY, "month", TODAY, IST, cohort_months=3
    )
    seg = {e["segment"]: (e["members"], e["share"]) for e in out["engagement"]}
    assert seg == {"HIGHLY_ENGAGED": (1, 0.5), "REGULAR": (0, 0.0), "LOW": (1, 0.5), "INACTIVE": (0, 0.0)}


@pytest.mark.parametrize(
    ("visits", "segment"),
    [
        (0, "INACTIVE"),
        (1, "LOW"),
        (3, "LOW"),
        (4, "REGULAR"),
        (11, "REGULAR"),
        (12, "HIGHLY_ENGAGED"),
        (30, "HIGHLY_ENGAGED"),
    ],
)
def test_engagement_thresholds(visits, segment):
    assert engagement_segment(visits) == segment


def test_retention_cohorts(data):
    members, subs, visits = data
    cohorts = compute_members(
        members, subs, visits, date(2026, 1, 1), TODAY, "month", TODAY, IST, cohort_months=3
    )["cohorts"]
    table = {c["cohort"]: (c["members"], [cell["rate"] for cell in c["retention"]]) for c in cohorts}
    assert table == {
        "2026-01": (2, [1.0, 0.5, 0.5]),  # member 2 cancelled on 31 Jan -> gone from February
        "2026-02": (1, [1.0, 1.0]),  # grace subscription still covers early March
        "2026-03": (1, [0.0]),  # only a PENDING_PAYMENT subscription -> never committed
    }


def test_members_empty_frames():
    out = compute_members(
        frame([]), frame([]), frame([]), TODAY - timedelta(days=6), TODAY, "day", TODAY, IST, 2
    )
    assert out["summary"]["active_members"] == 0
    assert len(out["series"]) == 7
    assert [c["members"] for c in out["cohorts"]] == [0, 0]
