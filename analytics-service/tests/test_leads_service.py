import json
from datetime import date

import pandas as pd
import pytest

from app.services.common import json_safe
from app.services.leads import (
    ACTIVITY_COLUMNS,
    LEAD_COLUMNS,
    LEAD_STATUSES,
    compute_funnel,
    compute_leads,
    compute_summary,
    conversion_by_source,
    follow_up_sla,
    lost_reasons,
    owner_leaderboard,
    prepare_leads,
    weekly_trend,
)
from tests.helpers import IST, frame, local_to_utc

FROM, TO = date(2026, 3, 2), date(2026, 3, 15)  # Mon 2 Mar .. Sun 15 Mar: two full weeks


def _lead(
    lid,
    created,
    status="NEW",
    *,
    source="WEBSITE",
    owner=None,
    owner_name=None,
    trial=None,
    converted=None,
    lost_reason=None,
    branch=1,
):
    return {
        "lead_id": lid,
        "branch_id": branch,
        "source": source,
        "status": status,
        "owner_user_id": owner,
        "owner_name": owner_name,
        "trial_subscription_id": trial,
        "converted_at": local_to_utc(converted) if converted else None,
        "lost_reason": lost_reason,
        "created_at": local_to_utc(created),
    }


def _act(lead, kind, when):
    return {"lead_id": lead, "type": kind, "created_at": local_to_utc(when)}


def _prep(rows):
    return prepare_leads(frame(rows, LEAD_COLUMNS), FROM, TO, IST)


@pytest.fixture
def leads():
    return frame(
        [
            # converted after a trial, 4 days after creation
            _lead(
                1,
                "2026-03-02 10:00",
                "CONVERTED",
                source="INSTAGRAM",
                owner=5,
                owner_name="Ravi",
                trial=100,
                converted="2026-03-06 10:00",
            ),
            # converted without a trial, 10 days after creation (in week 2)
            _lead(
                2,
                "2026-03-03 09:00",
                "CONVERTED",
                source="WEBSITE",
                owner=5,
                owner_name="Ravi",
                converted="2026-03-13 09:00",
            ),
            # trial that did not convert (yet)
            _lead(
                3,
                "2026-03-04 12:00",
                "TRIAL_ACTIVE",
                source="INSTAGRAM",
                owner=6,
                owner_name="Meera",
                trial=101,
            ),
            _lead(
                4,
                "2026-03-10 08:00",
                "LOST",
                source="INSTAGRAM",
                owner=6,
                owner_name="Meera",
                lost_reason="PRICE",
            ),
            _lead(5, "2026-03-11 08:00", "LOST", source="WALK_IN", lost_reason="PRICE"),
            _lead(
                6,
                "2026-03-12 08:00",
                "LOST",
                source="WALK_IN",
                owner=7,
                owner_name="Zed",
                lost_reason="TIMING",
            ),
            _lead(7, "2026-03-14 20:00", "CONTACTED", source="WEBSITE", owner=7, owner_name="Zed"),
            # outside the range: local date is 1 Mar (18:00 UTC on 1 Mar)
            _lead(8, "2026-03-01 23:30", "NEW", source="WEBSITE"),
            # 15 Mar 23:30 IST is still inside the range (18:00 UTC)
            _lead(9, "2026-03-15 23:30", "NEW", source="REFERRAL"),
        ],
        LEAD_COLUMNS,
    )


@pytest.fixture
def activities():
    return frame(
        [
            _act(1, "NOTE", "2026-03-02 10:05"),  # notes are not contact
            _act(1, "CALL", "2026-03-02 12:00"),  # 2h
            _act(1, "EMAIL", "2026-03-02 11:00"),  # 1h -> first contact
            _act(2, "STATUS_CHANGE", "2026-03-03 09:30"),  # status changes are not contact
            _act(2, "WHATSAPP", "2026-03-05 09:00"),  # 48h -> after the SLA
            _act(3, "VISIT", "2026-03-05 12:00"),  # exactly 24h -> within
            _act(4, "FOLLOW_UP", "2026-03-10 09:00"),  # not contact
            _act(4, "TOUR", "2026-03-10 09:30"),  # not contact
            _act(8, "CALL", "2026-03-02 00:00"),  # lead outside the range is ignored
        ],
        ACTIVITY_COLUMNS,
    )


def test_range_filter_uses_business_timezone(leads):
    df = prepare_leads(leads, FROM, TO, IST)
    assert sorted(df["lead_id"]) == [1, 2, 3, 4, 5, 6, 7, 9]


def test_funnel_has_all_statuses_in_pipeline_order(leads):
    funnel = compute_funnel(prepare_leads(leads, FROM, TO, IST))
    assert funnel["total_leads"] == 8
    assert [s["status"] for s in funnel["stages"]] == list(LEAD_STATUSES)
    counts = {s["status"]: s["count"] for s in funnel["stages"]}
    assert counts == {
        "NEW": 1,
        "CONTACTED": 1,
        "TOUR_SCHEDULED": 0,
        "TRIAL_ACTIVE": 1,
        "CONVERTED": 2,
        "LOST": 3,
    }
    assert funnel["stages"][5]["share"] == 0.375


def test_summary_rates_and_median(leads):
    s = compute_summary(prepare_leads(leads, FROM, TO, IST))
    assert s["total_leads"] == 8 and s["conversions"] == 2 and s["conversion_rate"] == 0.25
    assert s["open_leads"] == 3 and s["lost_leads"] == 3
    assert s["trial_leads"] == 2 and s["trial_conversions"] == 1 and s["trial_to_paid_rate"] == 0.5
    assert s["median_days_to_convert"] == 7.0  # median of 4 and 10 days
    assert s["unassigned_leads"] == 2


def test_median_days_odd_count_and_fractional():
    df = _prep(
        [
            _lead(1, "2026-03-02 10:00", "CONVERTED", converted="2026-03-03 22:00"),  # 1.5 days
            _lead(2, "2026-03-02 10:00", "CONVERTED", converted="2026-03-04 10:00"),  # 2
            _lead(3, "2026-03-02 10:00", "CONVERTED", converted="2026-03-12 10:00"),  # 10
            _lead(4, "2026-03-02 10:00", "LOST", lost_reason="OTHER"),
        ]
    )
    assert compute_summary(df)["median_days_to_convert"] == 2.0


def test_conversion_by_source_sorted_by_leads_desc(leads):
    rows = conversion_by_source(prepare_leads(leads, FROM, TO, IST))
    assert [(r["source"], r["leads"], r["conversions"], r["conversion_rate"]) for r in rows] == [
        ("INSTAGRAM", 3, 1, 0.3333),
        ("WEBSITE", 2, 1, 0.5),
        ("WALK_IN", 2, 0, 0.0),
        ("REFERRAL", 1, 0, 0.0),
    ]


def test_weekly_trend_fills_empty_weeks(leads):
    rows = weekly_trend(prepare_leads(leads, FROM, TO, IST), FROM, TO, IST)
    assert rows == [
        {"week_start": "2026-03-02", "leads": 3, "conversions": 1},
        {"week_start": "2026-03-09", "leads": 5, "conversions": 1},
    ]

    wider_to = date(2026, 3, 29)
    df = prepare_leads(leads, FROM, wider_to, IST)
    rows = weekly_trend(df, FROM, wider_to, IST)
    assert [r["week_start"] for r in rows] == ["2026-03-02", "2026-03-09", "2026-03-16", "2026-03-23"]
    assert rows[2] == {"week_start": "2026-03-16", "leads": 0, "conversions": 0}
    assert rows[3]["leads"] == 0


def test_weekly_trend_ignores_conversions_after_range():
    df = _prep([_lead(1, "2026-03-14 10:00", "CONVERTED", converted="2026-03-20 10:00")])
    rows = weekly_trend(df, FROM, TO, IST)
    assert sum(r["leads"] for r in rows) == 1 and sum(r["conversions"] for r in rows) == 0


def test_lost_reasons_sorted_desc(leads):
    rows = lost_reasons(prepare_leads(leads, FROM, TO, IST))
    assert rows == [
        {"reason": "PRICE", "count": 2, "share": 0.6667},
        {"reason": "TIMING", "count": 1, "share": 0.3333},
    ]


def test_owner_leaderboard_sorted_and_skips_unassigned(leads):
    rows = owner_leaderboard(prepare_leads(leads, FROM, TO, IST))
    assert [(r["owner_user_id"], r["owner_name"], r["leads_handled"], r["conversions"]) for r in rows] == [
        (5, "Ravi", 2, 2),
        (6, "Meera", 2, 0),  # ties on conversions and leads -> by name
        (7, "Zed", 2, 0),
    ]
    assert rows[0]["conversion_rate"] == 1.0 and rows[1]["conversion_rate"] == 0.0


def test_owner_leaderboard_float_owner_ids_and_missing_name():
    df = _prep(
        [
            _lead(1, "2026-03-02 10:00", owner=9.0, owner_name=None),
            _lead(2, "2026-03-02 10:00", owner=None),
            _lead(
                3,
                "2026-03-03 10:00",
                owner=9.0,
                owner_name=None,
                status="CONVERTED",
                converted="2026-03-04 10:00",
            ),
        ]
    )
    (row,) = owner_leaderboard(df)
    assert row == {
        "owner_user_id": 9,
        "owner_name": None,
        "leads_handled": 2,
        "conversions": 1,
        "conversion_rate": 0.5,
    }


def test_follow_up_sla(leads, activities):
    sla = follow_up_sla(prepare_leads(leads, FROM, TO, IST), activities)
    # contacted: 1 (1h), 2 (48h), 3 (24h); lead 4 only has FOLLOW_UP/TOUR; 5,6,7,9 nothing
    assert sla == {
        "sla_hours": 24,
        "total_leads": 8,
        "contacted_leads": 3,
        "contacted_within_24h": 2,
        "rate": 0.25,
        "median_hours_to_first_contact": 24.0,
    }


def test_follow_up_sla_only_non_contact_activities():
    df = _prep([_lead(1, "2026-03-02 10:00")])
    acts = frame([_act(1, "NOTE", "2026-03-02 10:01"), _act(1, "STATUS_CHANGE", "2026-03-02 10:02")])
    sla = follow_up_sla(df, acts)
    assert sla["contacted_leads"] == 0 and sla["contacted_within_24h"] == 0
    assert sla["rate"] == 0.0 and sla["median_hours_to_first_contact"] is None


def test_follow_up_sla_contact_just_after_24h():
    df = _prep([_lead(1, "2026-03-02 10:00")])
    sla = follow_up_sla(df, frame([_act(1, "SMS", "2026-03-03 10:01")]))
    assert sla["contacted_leads"] == 1 and sla["contacted_within_24h"] == 0 and sla["rate"] == 0.0
    assert sla["median_hours_to_first_contact"] == 24.0  # 24.0167h rounded to 1 decimal


def test_follow_up_sla_without_activity_frame():
    df = _prep([_lead(1, "2026-03-02 10:00")])
    sla = follow_up_sla(df, None)
    assert sla["total_leads"] == 1 and sla["contacted_leads"] == 0 and sla["rate"] == 0.0


@pytest.mark.parametrize("leads_df", [None, pd.DataFrame(), frame([], LEAD_COLUMNS)])
@pytest.mark.parametrize("acts_df", [None, pd.DataFrame(), frame([], ACTIVITY_COLUMNS)])
def test_empty_inputs(leads_df, acts_df):
    out = compute_leads(leads_df, acts_df, FROM, TO, IST)
    s = out["summary"]
    assert s["total_leads"] == 0 and s["conversions"] == 0
    assert s["conversion_rate"] is None and s["trial_to_paid_rate"] is None
    assert s["median_days_to_convert"] is None and s["unassigned_leads"] == 0
    assert out["funnel"]["total_leads"] == 0
    assert [(st["status"], st["count"], st["share"]) for st in out["funnel"]["stages"]] == [
        (st, 0, None) for st in LEAD_STATUSES
    ]
    assert out["by_source"] == [] and out["lost_reasons"] == [] and out["owner_leaderboard"] == []
    assert out["weekly_trend"] == [
        {"week_start": "2026-03-02", "leads": 0, "conversions": 0},
        {"week_start": "2026-03-09", "leads": 0, "conversions": 0},
    ]
    assert out["follow_up_sla"] == {
        "sla_hours": 24,
        "total_leads": 0,
        "contacted_leads": 0,
        "contacted_within_24h": 0,
        "rate": None,
        "median_hours_to_first_contact": None,
    }
    json.dumps(json_safe(out), allow_nan=False)


def test_all_leads_outside_range_behaves_like_empty(leads):
    out = compute_leads(leads, None, date(2027, 1, 1), date(2027, 1, 3), IST)
    assert out["summary"]["total_leads"] == 0 and out["by_source"] == []


def test_compute_leads_is_json_safe(leads, activities):
    out = compute_leads(leads, activities, FROM, TO, IST)
    text = json.dumps(json_safe(out), allow_nan=False)
    assert "NaN" not in text
    assert out["summary"]["total_leads"] == 8
    assert out["follow_up_sla"]["contacted_within_24h"] == 2
