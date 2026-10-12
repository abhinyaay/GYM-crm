from datetime import date

from app.services.branches import compute_branches
from tests.helpers import IST, frame, local_to_utc, member, sub, utc

TODAY = date(2026, 3, 15)


def test_branch_comparison_and_ranking():
    branches = frame(
        [
            {
                "branch_id": 1,
                "code": "B1",
                "name": "One",
                "capacity": 10,
                "timezone": IST,
                "status": "ACTIVE",
            },
            {"branch_id": 2, "code": "B2", "name": "Two", "capacity": 5, "timezone": IST, "status": "ACTIVE"},
        ]
    )
    members = frame(
        [
            member(1, utc("2026-03-05T05:00:00"), 1),
            member(2, utc("2026-01-01T05:00:00"), 1),
            member(3, utc("2026-02-01T05:00:00"), 2),
        ]
    )
    subs = frame(
        [
            sub(i, i, "ACTIVE", date(2026, 1, 1), date(2026, 12, 31), branch=b)
            for i, b in ((1, 1), (2, 1), (3, 2))
        ]
    )
    visits = frame(
        [
            {
                "id": i,
                "member_id": 1,
                "branch_id": 1,
                "check_in_time": local_to_utc(f"2026-03-0{i} 18:00"),
                "check_out_time": local_to_utc(f"2026-03-0{i} 19:00"),
                "auto_checked_out": False,
                "timezone": IST,
            }
            for i in range(1, 5)
        ]
        + [
            {
                "id": 9,
                "member_id": 3,
                "branch_id": 2,
                "check_in_time": local_to_utc("2026-03-02 07:00"),
                "check_out_time": local_to_utc("2026-03-02 08:00"),
                "auto_checked_out": False,
                "timezone": IST,
            }
        ]
    )
    payments = frame(
        [
            {
                "payment_id": 1,
                "member_id": 1,
                "amount": 1000.0,
                "refunded_amount": 0.0,
                "method": "UPI",
                "status": "SUCCEEDED",
                "processed_at": utc("2026-03-05T06:00:00"),
                "branch_id": 1,
                "branch_name": "One",
                "plan_id": 1,
                "plan_name": "Monthly",
            },
            {
                "payment_id": 2,
                "member_id": 3,
                "amount": 3000.0,
                "refunded_amount": 500.0,
                "method": "CARD",
                "status": "PARTIALLY_REFUNDED",
                "processed_at": utc("2026-03-06T06:00:00"),
                "branch_id": 2,
                "branch_name": "Two",
                "plan_id": 1,
                "plan_name": "Monthly",
            },
        ]
    )
    refunds = frame(
        [
            {
                "refund_id": 1,
                "payment_id": 2,
                "amount": 500.0,
                "processed_at": utc("2026-03-07T06:00:00"),
                "branch_id": 2,
                "branch_name": "Two",
                "plan_id": 1,
                "plan_name": "Monthly",
            }
        ]
    )
    complaints = frame(
        [
            {
                "complaint_id": i,
                "branch_id": 1,
                "member_id": None,
                "status": "OPEN",
                "created_at": utc("2026-03-01T00:00:00"),
            }
            for i in (1, 2)
        ]
    )

    out = compute_branches(
        branches,
        members,
        subs,
        visits,
        payments,
        refunds,
        complaints,
        date(2026, 3, 1),
        date(2026, 3, 14),
        TODAY,
        IST,
    )
    first, second = out["branches"]
    assert (first["branch_id"], first["revenue_rank"], first["net_revenue"]) == (2, 1, 2500.0)
    assert (second["branch_id"], second["revenue_rank"], second["net_revenue"]) == (1, 2, 1000.0)
    assert second["active_members"] == 2 and second["new_members"] == 1
    assert second["visits"] == 4 and second["visits_per_active_member"] == 2.0
    assert second["revenue_per_active_member"] == 500.0
    assert second["open_complaints"] == 2 and first["open_complaints"] == 0
    # 4 one-hour visits at 18:00 over 14 days -> 4/14 average occupancy at the peak hour
    assert second["peak_avg_occupancy"] == 0.29
    assert second["peak_occupancy_pct"] == 2.86
    assert out["summary"] == {
        "branches": 2,
        "active_members": 3,
        "visits": 5,
        "net_revenue": 3500.0,
        "open_complaints": 2,
    }
