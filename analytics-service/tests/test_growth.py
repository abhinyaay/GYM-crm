"""Referral & campaign analytics: pure service maths plus the API contract (fake repository)."""

from datetime import date

import pandas as pd
import pytest

from app.api.routers import growth as growth_router
from app.main import app
from app.services.growth import compute_growth
from tests.conftest import Recorder, auth

ADMIN = auth(["GYM_ADMIN"])
URL = "/analytics/growth"
RANGE = {"from": "2026-03-01", "to": "2026-03-15"}
TZ = "Asia/Kolkata"


def _ts(value: str | None):
    return None if value is None else pd.Timestamp(value, tz="UTC")


def _ref(rid, branch, channel, status, created, converted=None, rejection=None, skipped=None):
    return {
        "referral_id": rid,
        "branch_id": branch,
        "channel": channel,
        "status": status,
        "rejection_reason": rejection,
        "referrer_reward_skipped": skipped,
        "created_at": _ts(created),
        "converted_at": _ts(converted),
    }


REFERRALS = pd.DataFrame(
    [
        _ref(1, 1, "LINK", "CONVERTED", "2026-03-01T05:00:00", "2026-03-05T05:00:00"),
        _ref(2, 1, "LINK", "PENDING", "2026-03-03T05:00:00"),
        _ref(3, 1, "DESK", "REVERSED", "2026-03-04T05:00:00", "2026-03-06T05:00:00"),
        _ref(4, 2, "DESK", "REJECTED", "2026-03-05T05:00:00", rejection="NOT_NEW"),
        _ref(
            5,
            2,
            "SELF_SIGNUP",
            "CONVERTED",
            "2026-03-06T05:00:00",
            "2026-03-12T05:00:00",
            skipped="MONTHLY_CAP",
        ),
        # created before the range (UTC margin row): excluded by the local-date filter
        _ref(6, 1, "LINK", "CONVERTED", "2026-02-27T05:00:00", "2026-03-02T05:00:00"),
    ]
)

REWARDS = pd.DataFrame(
    [
        {
            "referral_id": 1,
            "beneficiary": "REFERRER",
            "reward_type": "STORE_CREDIT",
            "amount": 500,
            "status": "AVAILABLE",
        },
        {
            "referral_id": 1,
            "beneficiary": "REFEREE",
            "reward_type": "DISCOUNT_CODE",
            "amount": 10,
            "status": "AVAILABLE",
        },
        {
            "referral_id": 3,
            "beneficiary": "REFERRER",
            "reward_type": "STORE_CREDIT",
            "amount": 500,
            "status": "REVERSED",
        },
        {
            "referral_id": 3,
            "beneficiary": "REFEREE",
            "reward_type": "DISCOUNT_CODE",
            "amount": 10,
            "status": "REVERSED",
        },
        {
            "referral_id": 5,
            "beneficiary": "REFEREE",
            "reward_type": "STORE_CREDIT",
            "amount": 200,
            "status": "HELD",
        },
        {
            "referral_id": 6,
            "beneficiary": "REFERRER",
            "reward_type": "STORE_CREDIT",
            "amount": 500,
            "status": "AVAILABLE",
        },
    ]
)


def _msg(
    cid, name, segment, member, sent, converted=None, amount=None, offer=False, redeemed=False, branch=1
):
    return {
        "campaign_id": cid,
        "campaign_name": name,
        "segment": segment,
        "member_id": member,
        "branch_id": branch,
        "sent_at": _ts(sent),
        "converted_at": _ts(converted),
        "conversion_amount": amount,
        "has_offer": offer,
        "offer_redeemed": redeemed,
    }


RECIPIENTS = pd.DataFrame(
    [
        _msg(10, "Win back", "LAPSED", 1, "2026-03-02T04:30:00", "2026-03-04T04:30:00", 1180.0, True, True),
        _msg(10, "Win back", "LAPSED", 2, "2026-03-02T04:30:00", offer=True),
        _msg(10, "Win back", "LAPSED", 3, "2026-03-02T04:30:00", offer=True),
        _msg(
            11,
            "Renewal nudge",
            "EXPIRING_NO_AUTOPAY",
            4,
            "2026-03-09T04:30:00",
            "2026-03-10T04:30:00",
            2360.0,
        ),
        _msg(
            11, "Renewal nudge", "EXPIRING_NO_AUTOPAY", 4, "2026-03-16T04:30:00", branch=2
        ),  # after the range
    ]
)


def test_referral_funnel_rates_and_rewards():
    out = compute_growth(REFERRALS, REWARDS, RECIPIENTS, date(2026, 3, 1), date(2026, 3, 15), TZ)
    r = out["referrals"]
    assert r["total"] == 5
    assert r["by_status"] == {"PENDING": 1, "CONVERTED": 2, "REVERSED": 1, "REJECTED": 1}
    assert r["conversion_rate"] == 0.75  # 3 converted at some point / 4 eligible
    assert r["reversal_rate"] == 0.3333
    assert r["capped"] == 1
    assert r["median_days_to_convert"] == 4.0  # 4, 2, 6 days
    assert out["by_channel"] == [
        {"channel": "LINK", "referrals": 2, "conversions": 1, "conversion_rate": 0.5},
        {"channel": "DESK", "referrals": 2, "conversions": 1, "conversion_rate": 1.0},
        {"channel": "SELF_SIGNUP", "referrals": 1, "conversions": 1, "conversion_rate": 1.0},
    ]
    assert out["rewards"] == {
        "credit_issued": 500.0,  # referral 6 is outside the range
        "credit_pending": 200.0,
        "credit_reversed": 500.0,
        "codes_issued": 1,
        "rewards_reversed": 2,
    }


def test_campaign_performance_segments_and_totals():
    out = compute_growth(REFERRALS, REWARDS, RECIPIENTS, date(2026, 3, 1), date(2026, 3, 15), TZ)
    assert out["campaigns"] == {
        "messages": 4,
        "conversions": 2,
        "conversion_rate": 0.5,
        "revenue": 3540.0,
        "revenue_per_message": 885.0,
    }
    perf = out["campaign_performance"]
    assert [p["campaign_id"] for p in perf] == [11, 10]  # by revenue
    assert perf[1] == {
        "campaign_id": 10,
        "name": "Win back",
        "segment": "LAPSED",
        "messages": 3,
        "members": 3,
        "conversions": 1,
        "conversion_rate": 0.3333,
        "revenue": 1180.0,
        "offers_issued": 3,
        "offers_redeemed": 1,
        "redemption_rate": 0.3333,
    }
    assert perf[0]["redemption_rate"] is None  # no offers
    assert [s["segment"] for s in out["by_segment"]] == ["EXPIRING_NO_AUTOPAY", "LAPSED"]


def test_empty_inputs_give_zeros_and_nulls():
    out = compute_growth(None, None, None, date(2026, 3, 1), date(2026, 3, 15), TZ)
    assert out["referrals"]["total"] == 0 and out["referrals"]["conversion_rate"] is None
    assert out["referrals"]["median_days_to_convert"] is None
    assert out["rewards"]["credit_issued"] == 0.0
    assert out["campaigns"]["conversion_rate"] is None and out["campaigns"]["revenue_per_message"] is None
    assert out["campaign_performance"] == [] and out["by_segment"] == []
    assert [c["referrals"] for c in out["by_channel"]] == [0, 0, 0]


# ------------------------------------------------------------------ API


class FakeGrowthRepository:
    def __init__(self, rec: Recorder) -> None:
        self.rec = rec

    @staticmethod
    def _scope(df, branch_ids):
        return df if branch_ids is None else df[df["branch_id"].isin(list(branch_ids))].reset_index(drop=True)

    def fetch_referrals(self, start, end, branch_ids):
        self.rec.record("growth.fetch_referrals", branch_ids)
        return self._scope(REFERRALS, branch_ids)

    def fetch_rewards(self, start, end, branch_ids):
        self.rec.record("growth.fetch_rewards", branch_ids)
        ids = set(self._scope(REFERRALS, branch_ids)["referral_id"])
        return REWARDS[REWARDS["referral_id"].isin(ids)].reset_index(drop=True)

    def fetch_recipients(self, start, end, branch_ids):
        self.rec.record("growth.fetch_recipients", branch_ids)
        return self._scope(RECIPIENTS, branch_ids).drop(columns=["branch_id"])


@pytest.fixture
def growth_client(client, recorder):
    app.dependency_overrides[growth_router.get_growth_repository] = lambda: FakeGrowthRepository(recorder)
    return client


def test_requires_analytics_role(growth_client):
    assert growth_client.get(URL).status_code == 401
    assert growth_client.get(URL, headers=auth(["MEMBER"], [1])).status_code == 403


def test_manager_is_scoped_to_assigned_branches(growth_client, recorder):
    body = growth_client.get(URL, params=RANGE, headers=auth(["MANAGER"], [1])).json()
    assert body["meta"]["scopeBranchIds"] == [1]
    assert recorder.calls == [
        ("growth.fetch_referrals", (1,)),
        ("growth.fetch_rewards", (1,)),
        ("growth.fetch_recipients", (1,)),
    ]
    assert body["referrals"]["total"] == 3
    assert growth_client.get(URL, params={"branchId": 2}, headers=auth(["MANAGER"], [1])).status_code == 403


def test_response_shape_is_camel_case(growth_client):
    resp = growth_client.get(URL, params=RANGE, headers=ADMIN)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert set(body) == {
        "meta",
        "referrals",
        "byChannel",
        "rewards",
        "campaigns",
        "campaignPerformance",
        "bySegment",
    }
    assert body["referrals"]["byStatus"]["CONVERTED"] == 2
    assert body["rewards"]["creditIssued"] == 500.0
    assert body["campaigns"]["revenuePerMessage"] == 885.0
    assert set(body["campaignPerformance"][0]) >= {"campaignId", "offersRedeemed", "redemptionRate"}
