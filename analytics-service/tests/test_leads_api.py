import pandas as pd
import pytest

from app.api.routers import leads as leads_router
from app.main import app
from tests.conftest import Recorder, auth

ADMIN = auth(["GYM_ADMIN"])
URL = "/analytics/leads"
RANGE = {"from": "2026-03-02", "to": "2026-03-15"}


def _ts(value: str) -> pd.Timestamp:
    return pd.Timestamp(value, tz="UTC")


LEADS = pd.DataFrame(
    [
        {
            "lead_id": 1,
            "branch_id": 1,
            "source": "INSTAGRAM",
            "status": "CONVERTED",
            "owner_user_id": 5,
            "owner_name": "Ravi Sales",
            "trial_subscription_id": 100,
            "converted_at": _ts("2026-03-06T04:30:00"),
            "lost_reason": None,
            "created_at": _ts("2026-03-02T04:30:00"),
        },
        {
            "lead_id": 2,
            "branch_id": 1,
            "source": "WALK_IN",
            "status": "LOST",
            "owner_user_id": None,
            "owner_name": None,
            "trial_subscription_id": None,
            "converted_at": None,
            "lost_reason": "PRICE",
            "created_at": _ts("2026-03-10T04:30:00"),
        },
        {
            "lead_id": 3,
            "branch_id": 2,
            "source": "WEBSITE",
            "status": "NEW",
            "owner_user_id": 6,
            "owner_name": "Dana Dubai",
            "trial_subscription_id": None,
            "converted_at": None,
            "lost_reason": None,
            "created_at": _ts("2026-03-11T06:00:00"),
        },
    ]
)

ACTIVITIES = pd.DataFrame(
    [
        {"lead_id": 1, "type": "CALL", "created_at": _ts("2026-03-02T06:30:00")},
        {"lead_id": 2, "type": "NOTE", "created_at": _ts("2026-03-10T05:00:00")},
        {"lead_id": 3, "type": "WHATSAPP", "created_at": _ts("2026-03-13T06:00:00")},
    ]
)


class FakeLeadRepository:
    def __init__(self, rec: Recorder) -> None:
        self.rec = rec

    def _lead_ids(self, branch_ids):
        df = LEADS if branch_ids is None else LEADS[LEADS["branch_id"].isin(list(branch_ids))]
        return set(df["lead_id"])

    def fetch_leads(self, start, end, branch_ids):
        self.rec.record("leads.fetch_leads", branch_ids)
        return LEADS[LEADS["lead_id"].isin(self._lead_ids(branch_ids))].reset_index(drop=True)

    def fetch_first_activities(self, start, end, branch_ids):
        self.rec.record("leads.fetch_first_activities", branch_ids)
        return ACTIVITIES[ACTIVITIES["lead_id"].isin(self._lead_ids(branch_ids))].reset_index(drop=True)


@pytest.fixture
def leads_client(client, recorder):
    # the shared ``client`` fixture clears dependency overrides on teardown
    app.dependency_overrides[leads_router.get_lead_repository] = lambda: FakeLeadRepository(recorder)
    return client


def test_requires_auth(leads_client):
    resp = leads_client.get(URL)
    assert resp.status_code == 401 and resp.json()["code"] == "UNAUTHORIZED"


@pytest.mark.parametrize("role", ["TRAINER", "RECEPTIONIST", "MEMBER"])
def test_non_analytics_roles_403(leads_client, role):
    assert leads_client.get(URL, headers=auth([role], [1])).status_code == 403


def test_manager_other_branch_403(leads_client, recorder):
    resp = leads_client.get(URL, params={"branchId": 2}, headers=auth(["MANAGER"], [1]))
    assert resp.status_code == 403 and resp.json()["code"] == "ACCESS_DENIED"
    assert recorder.calls == []  # no data was read


def test_manager_scoped_to_assigned_branches(leads_client, recorder):
    body = leads_client.get(URL, params=RANGE, headers=auth(["MANAGER"], [1])).json()
    assert body["meta"]["scopeBranchIds"] == [1]
    assert recorder.calls == [("leads.fetch_leads", (1,)), ("leads.fetch_first_activities", (1,))]
    assert body["summary"]["totalLeads"] == 2


def test_default_range_is_last_90_days(leads_client):
    meta = leads_client.get(URL, headers=ADMIN).json()["meta"]
    assert (meta["from"], meta["to"]) == ("2025-12-16", "2026-03-15")


@pytest.mark.parametrize(
    "params",
    [
        {"from": "2026-03-10", "to": "2026-03-01"},
        {"from": "2023-01-01", "to": "2026-03-01"},
        {"from": "not-a-date"},
        {"branchId": 0},
    ],
)
def test_validation_errors(leads_client, params):
    resp = leads_client.get(URL, params=params, headers=ADMIN)
    assert resp.status_code == 400 and resp.json()["code"] == "VALIDATION_FAILED"


def test_happy_path_response_shape(leads_client):
    resp = leads_client.get(URL, params=RANGE, headers=ADMIN)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert set(body) == {
        "meta",
        "summary",
        "funnel",
        "bySource",
        "weeklyTrend",
        "lostReasons",
        "ownerLeaderboard",
        "followUpSla",
    }
    assert body["meta"]["from"] == "2026-03-02" and body["meta"]["timezone"] == "Asia/Kolkata"
    assert body["summary"] == {
        "totalLeads": 3,
        "openLeads": 1,
        "conversions": 1,
        "conversionRate": 0.3333,
        "lostLeads": 1,
        "trialLeads": 1,
        "trialConversions": 1,
        "trialToPaidRate": 1.0,
        "medianDaysToConvert": 4.0,
        "unassignedLeads": 1,
    }
    assert body["funnel"]["totalLeads"] == 3
    assert [s["status"] for s in body["funnel"]["stages"]] == [
        "NEW",
        "CONTACTED",
        "TOUR_SCHEDULED",
        "TRIAL_ACTIVE",
        "CONVERTED",
        "LOST",
    ]
    assert body["funnel"]["stages"][1] == {"status": "CONTACTED", "count": 0, "share": 0.0}
    assert set(body["bySource"][0]) == {"source", "leads", "conversions", "conversionRate"}
    assert body["weeklyTrend"] == [
        {"weekStart": "2026-03-02", "leads": 1, "conversions": 1},
        {"weekStart": "2026-03-09", "leads": 2, "conversions": 0},
    ]
    assert body["lostReasons"] == [{"reason": "PRICE", "count": 1, "share": 1.0}]
    assert body["ownerLeaderboard"] == [
        {
            "ownerUserId": 5,
            "ownerName": "Ravi Sales",
            "leadsHandled": 1,
            "conversions": 1,
            "conversionRate": 1.0,
        },
        {
            "ownerUserId": 6,
            "ownerName": "Dana Dubai",
            "leadsHandled": 1,
            "conversions": 0,
            "conversionRate": 0.0,
        },
    ]
    assert body["followUpSla"] == {
        "slaHours": 24,
        "totalLeads": 3,
        "contactedLeads": 2,
        "contactedWithin24h": 1,
        "rate": 0.3333,
        "medianHoursToFirstContact": 25.0,
    }


def test_empty_data_returns_nulls_not_nan(leads_client):
    body = leads_client.get(URL, params={"from": "2025-01-06", "to": "2025-01-12"}, headers=ADMIN).json()
    assert body["summary"]["totalLeads"] == 0 and body["summary"]["conversionRate"] is None
    assert body["followUpSla"]["rate"] is None
    assert body["weeklyTrend"] == [{"weekStart": "2025-01-06", "leads": 0, "conversions": 0}]


def test_responses_are_cached_per_scope(leads_client, recorder):
    leads_client.get(URL, headers=ADMIN)
    leads_client.get(URL, headers=ADMIN)
    assert len(recorder.calls) == 2
    leads_client.get(URL, headers=auth(["MANAGER"], [1]))
    assert len(recorder.calls) == 4


def test_openapi_documents_leads(leads_client):
    paths = leads_client.get("/openapi.json").json()["paths"]
    params = {p["name"] for p in paths[URL]["get"]["parameters"]}
    assert {"branchId", "from", "to"} <= params
