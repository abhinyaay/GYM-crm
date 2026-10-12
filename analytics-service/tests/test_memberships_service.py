from datetime import date

import pytest

from app.services.memberships import churn_stats, compute_memberships, month_summary, renewal_stats
from app.services.subscriptions import prepare_subscriptions
from tests.helpers import IST, frame, sub, utc

TODAY = date(2026, 4, 15)
MARCH = (date(2026, 3, 1), date(2026, 3, 31))


@pytest.fixture
def subs():
    return frame(
        [
            # m1: expired 10 Mar, renewed via previous_subscription_id
            sub(1, 1, "EXPIRED", date(2026, 2, 1), date(2026, 3, 10), grace=3),
            sub(
                2,
                1,
                "ACTIVE",
                date(2026, 3, 11),
                date(2026, 5, 10),
                prev=1,
                change_type="RENEWAL",
                frozen_from=date(2026, 3, 20),
                frozen_until=date(2026, 3, 24),
                freeze_count=1,
                freeze_days=5,
            ),
            # m2: expired 14 Mar, nothing after -> churned
            sub(3, 2, "EXPIRED", date(2026, 2, 15), date(2026, 3, 14), grace=3),
            # m3: expired 20 Mar, bought a NEW (unlinked) plan within grace -> retained, but not a "renewal"
            sub(4, 3, "EXPIRED", date(2026, 2, 1), date(2026, 3, 20), grace=5),
            sub(5, 3, "ACTIVE", date(2026, 3, 24), date(2026, 4, 23)),
            # m4: cancelled on 5 Mar after activation -> access ended in March -> churned
            sub(
                6,
                4,
                "CANCELLED",
                date(2026, 2, 10),
                date(2026, 5, 9),
                cancelled_at=utc("2026-03-05T06:00:00"),
            ),
            # m5: upgraded on 15 Mar; superseded term is cut at the day before the upgrade starts
            sub(7, 5, "SUPERSEDED", date(2026, 1, 1), date(2026, 6, 30), grace=3),
            sub(
                8,
                5,
                "ACTIVE",
                date(2026, 3, 15),
                date(2026, 9, 14),
                prev=7,
                change_type="UPGRADE",
                plan=2,
                plan_name="Annual",
            ),
            # m6: started mid-March -> not in the March starting population
            sub(9, 6, "ACTIVE", date(2026, 3, 10), date(2026, 4, 9)),
            # m7: never paid
            sub(10, 7, "PENDING_PAYMENT", date(2026, 2, 1), date(2026, 3, 31), activated=False),
            # m8: ended 31 Mar with a 30-day grace window still open on 15 Apr -> pending
            sub(11, 8, "EXPIRED", date(2026, 3, 1), date(2026, 3, 31), grace=30),
        ]
    )


def test_churn_formula(subs):
    out = churn_stats(prepare_subscriptions(subs, IST), *MARCH, TODAY)
    assert out == {"members_at_start": 6, "churned_members": 2, "pending_members": 1, "churn_rate": 0.3333}


def test_renewal_formula(subs):
    out = renewal_stats(prepare_subscriptions(subs, IST), *MARCH, TODAY)
    assert out == {"ended_subscriptions": 4, "renewed_subscriptions": 1, "renewal_rate": 0.25}


def test_superseded_effective_end_is_cut_at_successor_start(subs):
    p = prepare_subscriptions(subs, IST).set_index("subscription_id")
    assert str(p.loc[7, "effective_end"].date()) == "2026-03-14"
    assert str(p.loc[6, "effective_end"].date()) == "2026-03-05"  # cancelled
    assert not p.loc[10, "committed"]


def test_month_summary_contract(subs):
    assert month_summary(subs, date(2026, 3, 1), TODAY, IST) == {
        "month": "2026-03",
        "churn_rate": 0.3333,
        "churned_members": 2,
        "members_at_start": 6,
        "renewal_rate": 0.25,
    }


def test_month_summary_with_no_members():
    assert month_summary(frame([]), date(2026, 3, 1), TODAY, IST) == {
        "month": "2026-03",
        "churn_rate": 0.0,
        "churned_members": 0,
        "members_at_start": 0,
        "renewal_rate": None,
    }


def test_compute_memberships(subs):
    out = compute_memberships(subs, *MARCH, TODAY, IST)
    (march,) = out["monthly"]
    assert march["month"] == "2026-03"
    assert march["renewal_rate"] == 0.25 and march["churn_rate"] == 0.3333
    assert march["subscriptions_frozen"] == 1 and march["frozen_days"] == 5
    assert march["new_subscriptions"] == 5  # subs 2, 5, 8, 9, 11 start in March (10 never committed)

    assert [(p["plan_name"], p["active_subscriptions"], p["share"]) for p in out["plan_mix"]] == [
        ("Monthly", 2, 0.6667),
        ("Annual", 1, 0.3333),
    ]
    assert out["upcoming_expiries"] == [
        {"within_days": 30, "expiring": 2, "with_renewal": 0, "without_renewal": 2},
        {"within_days": 60, "expiring": 2, "with_renewal": 0, "without_renewal": 2},
        {"within_days": 90, "expiring": 2, "with_renewal": 0, "without_renewal": 2},
    ]
    assert out["freeze_usage"] == {
        "currently_frozen": 0,
        "active_subscriptions_with_freeze_used": 1,
        "share_with_freeze_used": 0.3333,
        "avg_freeze_days_used": 5.0,
    }
    assert out["cancellations_by_plan"] == [
        {"plan_id": 1, "plan_name": "Monthly", "cancellations": 1, "after_activation": 1}
    ]
    assert out["summary"]["renewal_rate"] == 0.25


def test_upcoming_expiry_with_renewal_in_place():
    subs = frame(
        [
            sub(1, 1, "ACTIVE", date(2026, 3, 20), date(2026, 4, 19)),
            sub(2, 1, "SCHEDULED", date(2026, 4, 20), date(2026, 5, 19), prev=1, change_type="RENEWAL"),
        ]
    )
    out = compute_memberships(subs, date(2026, 4, 1), TODAY, TODAY, IST)
    assert out["upcoming_expiries"][0] == {
        "within_days": 30,
        "expiring": 1,
        "with_renewal": 1,
        "without_renewal": 0,
    }
