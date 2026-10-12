from datetime import date

import pytest

from app.services.revenue import compute_revenue, net_revenue_by_branch
from tests.helpers import IST, frame, utc


def _pay(pid, member, amount, status, method, at, plan=None, plan_name=None, branch=1, refunded=0.0):
    return {
        "payment_id": pid,
        "member_id": member,
        "amount": amount,
        "refunded_amount": refunded,
        "method": method,
        "status": status,
        "processed_at": at,
        "branch_id": branch,
        "branch_name": f"Branch {branch}",
        "plan_id": plan,
        "plan_name": plan_name,
    }


@pytest.fixture
def payments():
    return frame(
        [
            _pay(1, 1, 1000.0, "SUCCEEDED", "UPI", utc("2026-03-05T06:00:00"), 100, "Monthly"),
            _pay(
                2,
                2,
                3000.0,
                "PARTIALLY_REFUNDED",
                "CARD",
                utc("2026-03-10T06:00:00"),
                200,
                "Quarterly",
                refunded=500.0,
            ),
            _pay(3, 1, 1000.0, "FAILED", "CARD", utc("2026-03-11T06:00:00"), 100, "Monthly"),
            # 31 Mar 19:00 UTC is 1 April in IST -> outside March
            _pay(4, 3, 500.0, "SUCCEEDED", "CASH", utc("2026-03-31T19:00:00"), 100, "Monthly"),
            # 28 Feb 19:00 UTC is 1 March in IST -> inside March; no subscription -> Unassigned plan
            _pay(5, 4, 800.0, "SUCCEEDED", "CASH", utc("2026-02-28T19:00:00"), None, None, branch=2),
        ]
    )


@pytest.fixture
def refunds():
    return frame(
        [
            {
                "refund_id": 1,
                "payment_id": 2,
                "amount": 500.0,
                "processed_at": utc("2026-03-12T06:00:00"),
                "branch_id": 1,
                "branch_name": "Branch 1",
                "plan_id": 200,
                "plan_name": "Quarterly",
            },
            {
                "refund_id": 2,
                "payment_id": 1,
                "amount": 100.0,
                "processed_at": utc("2026-04-02T06:00:00"),
                "branch_id": 1,
                "branch_name": "Branch 1",
                "plan_id": 100,
                "plan_name": "Monthly",
            },
        ]
    )


def test_revenue_net_of_refunds(payments, refunds):
    out = compute_revenue(payments, refunds, date(2026, 3, 1), date(2026, 3, 31), "month", IST)
    s = out["summary"]
    assert (s["gross"], s["refunds"], s["net"]) == (4800.0, 500.0, 4300.0)
    assert s["successful_payments"] == 3 and s["failed_payments"] == 1 and s["payment_attempts"] == 4
    assert s["failed_payment_rate"] == 0.25
    assert s["paying_members"] == 3
    assert s["avg_revenue_per_paying_member"] == 1433.33
    assert out["series"] == [
        {
            "period": "2026-03-01",
            "gross": 4800.0,
            "refunds": 500.0,
            "net": 4300.0,
            "successful_payments": 3,
            "failed_payments": 1,
        }
    ]


def test_revenue_by_plan_and_branch(payments, refunds):
    out = compute_revenue(payments, refunds, date(2026, 3, 1), date(2026, 3, 31), "month", IST)
    plans = [(p["plan_id"], p["plan_name"], p["gross"], p["refunds"], p["net"]) for p in out["by_plan"]]
    assert plans == [
        (200, "Quarterly", 3000.0, 500.0, 2500.0),
        (100, "Monthly", 1000.0, 0.0, 1000.0),
        (None, "Unassigned", 800.0, 0.0, 800.0),
    ]
    assert out["by_plan"][0]["share_of_net"] == 0.5814
    branches = {b["branch_id"]: b["net"] for b in out["by_branch"]}
    assert branches == {1: 3500.0, 2: 800.0}


def test_payment_method_mix(payments, refunds):
    out = compute_revenue(payments, refunds, date(2026, 3, 1), date(2026, 3, 31), "month", IST)
    mix = {m["method"]: m for m in out["payment_methods"]}
    assert list(mix) == ["CARD", "UPI", "CASH"]
    assert mix["CARD"]["amount"] == 3000.0 and mix["CARD"]["payments"] == 1  # failed CARD attempt excluded
    assert mix["CARD"]["share_of_amount"] == 0.625


def test_refund_only_period_and_daily_series(payments, refunds):
    out = compute_revenue(payments, refunds, date(2026, 4, 1), date(2026, 4, 2), "day", IST)
    assert [(p["period"], p["gross"], p["refunds"], p["net"]) for p in out["series"]] == [
        ("2026-04-01", 500.0, 0.0, 500.0),
        ("2026-04-02", 0.0, 100.0, -100.0),
    ]


def test_net_revenue_by_branch(payments, refunds):
    by_branch = net_revenue_by_branch(payments, refunds, date(2026, 3, 1), date(2026, 3, 31), IST)
    assert by_branch.loc[1, "net"] == 3500.0
    assert by_branch.loc[2, "gross"] == 800.0


def test_empty_revenue():
    out = compute_revenue(frame([]), frame([]), date(2026, 3, 1), date(2026, 3, 2), "day", IST)
    assert out["summary"]["net"] == 0.0
    assert out["summary"]["failed_payment_rate"] is None
    assert out["by_plan"] == [] and out["payment_methods"] == []
