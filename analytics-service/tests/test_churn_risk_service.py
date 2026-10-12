from datetime import date

import pytest

from app.services import churn_risk
from app.services.churn_risk import RULES, band_for, paginate, score_members
from tests.helpers import IST, frame, member, sub, utc

TODAY = date(2026, 3, 15)


def _visits(member_id, days):
    return [{"member_id": member_id, "check_in_time": utc(f"{d}T05:00:00")} for d in days]


@pytest.fixture
def scored():
    members = frame([member(i, utc("2025-12-01T05:00:00"), name=f"Member {i}") for i in range(1, 6)])
    subs = frame(
        [
            sub(1, 1, "ACTIVE", date(2026, 1, 1), date(2026, 6, 30)),
            sub(2, 2, "ACTIVE", date(2026, 2, 20), date(2026, 3, 20)),  # ends in 5 days
            sub(3, 3, "ACTIVE", date(2026, 3, 1), date(2026, 3, 25), freeze_count=1, freeze_days=3),
            sub(4, 3, "SCHEDULED", date(2026, 3, 26), date(2026, 4, 25), prev=3, change_type="RENEWAL"),
            sub(5, 4, "GRACE", date(2026, 2, 1), date(2026, 3, 10), grace=7),
            sub(6, 5, "FROZEN", date(2026, 1, 1), date(2026, 6, 30)),  # not scored
        ]
    )
    visits = (
        _visits(1, ["2026-03-01", "2026-03-14"])
        + _visits(2, ["2026-01-20", "2026-01-25", "2026-01-30", "2026-02-04", "2026-02-08", "2026-02-10"])
        + _visits(3, ["2026-02-28"])
    )
    visits_df = frame(visits)
    last = (
        visits_df.groupby("member_id", as_index=False)["check_in_time"]
        .max()
        .rename(columns={"check_in_time": "last_check_in"})
    )
    failed = frame(
        [
            {"payment_id": 9, "member_id": 2, "processed_at": utc("2026-03-05T06:00:00")},
            {"payment_id": 8, "member_id": 1, "processed_at": utc("2026-01-05T06:00:00")},
        ]
    )  # too old
    complaints = frame(
        [{"complaint_id": 1, "member_id": 2, "status": "OPEN", "created_at": utc("2026-03-01T06:00:00")}]
    )
    return score_members(members, subs, visits_df, last, failed, complaints, TODAY, IST)


def test_scores_bands_and_order(scored):
    rows = scored.set_index("member_id")
    assert list(scored["member_id"]) == [2, 4, 3, 1]  # member 5 (FROZEN) is not scored
    assert rows.loc[2, "score"] == 100  # 35+20+25+15+10 = 105, capped
    assert rows.loc[4, "score"] == 60 and rows.loc[4, "band"] == "HIGH"
    assert rows.loc[3, "score"] == 30 and rows.loc[3, "band"] == "MEDIUM"
    assert rows.loc[1, "score"] == 0 and rows.loc[1, "band"] == "LOW"


def test_triggered_reasons_are_explained(scored):
    rows = scored.set_index("member_id")
    reasons = {r["code"]: r for r in rows.loc[2, "reasons"]}
    assert set(reasons) == {
        "NO_VISIT_30_DAYS",
        "VISIT_FREQUENCY_DROP",
        "EXPIRING_WITHOUT_RENEWAL",
        "RECENT_FAILED_PAYMENT",
        "OPEN_COMPLAINT",
    }
    assert reasons["NO_VISIT_30_DAYS"]["description"] == "No visit in 33 days"
    assert (
        reasons["EXPIRING_WITHOUT_RENEWAL"]["description"]
        == "Subscription ends in 5 days with no renewal in place"
    )
    assert reasons["RECENT_FAILED_PAYMENT"]["description"] == "Failed payment on 2026-03-05"
    assert reasons["VISIT_FREQUENCY_DROP"]["points"] == RULES["VISIT_FREQUENCY_DROP"].weight
    assert rows.loc[2, "days_since_last_visit"] == 33
    assert rows.loc[2, "subscription_end_date"] == "2026-03-20"
    assert rows.loc[2, "last_visit"].startswith("2026-02-10")

    # member 3 renews (SCHEDULED successor) -> no expiry rule; idle 15 days and frozen once
    assert [r["code"] for r in rows.loc[3, "reasons"]] == ["NO_VISIT_14_DAYS", "FROZEN_THIS_TERM"]
    # member 4 never visited: measured from subscription start; in grace without renewal
    codes4 = [r["code"] for r in rows.loc[4, "reasons"]]
    assert codes4 == ["NO_VISIT_30_DAYS", "EXPIRING_WITHOUT_RENEWAL"]
    assert rows.loc[4, "last_visit"] is None
    assert "grace period" in rows.loc[4, "reasons"][1]["description"]


def test_band_thresholds():
    assert [band_for(s) for s in (0, 29, 30, 59, 60, 100)] == [
        "LOW",
        "LOW",
        "MEDIUM",
        "MEDIUM",
        "HIGH",
        "HIGH",
    ]


def test_weights_are_documented_in_one_place():
    assert {code: rule.weight for code, rule in RULES.items()} == {
        "NO_VISIT_30_DAYS": 35,
        "NO_VISIT_14_DAYS": 20,
        "VISIT_FREQUENCY_DROP": 20,
        "EXPIRING_WITHOUT_RENEWAL": 25,
        "RECENT_FAILED_PAYMENT": 15,
        "OPEN_COMPLAINT": 10,
        "FROZEN_THIS_TERM": 10,
    }
    assert len(churn_risk.rules_catalogue()) == len(RULES)


def test_paginate_filters_by_min_band(scored):
    page = paginate(scored, "MEDIUM", 0, 2)
    assert [i["member_id"] for i in page["items"]] == [2, 4]
    assert page["total_items"] == 3 and page["total_pages"] == 2
    assert page["band_counts"] == {"HIGH": 2, "MEDIUM": 1, "LOW": 1}
    assert [i["member_id"] for i in paginate(scored, "MEDIUM", 1, 2)["items"]] == [3]
    assert paginate(scored, "HIGH", 5, 2)["items"] == []


def test_no_scored_members():
    empty = score_members(frame([]), frame([]), frame([]), frame([]), frame([]), frame([]), TODAY, IST)
    page = paginate(empty, "LOW", 0, 50)
    assert page["items"] == [] and page["total_items"] == 0 and page["total_pages"] == 0
