import pytest
from sqlalchemy.exc import OperationalError

from app.api import deps
from app.main import app
from tests.conftest import FakeRevenueRepository, auth

ADMIN = auth(["GYM_ADMIN"])

ENDPOINTS = [
    "/analytics/attendance",
    "/analytics/attendance/peak-hours",
    "/analytics/revenue",
    "/analytics/members",
    "/analytics/members/churn-risk",
    "/analytics/memberships",
    "/analytics/memberships/churn",
    "/analytics/trainers",
    "/analytics/branches",
]


@pytest.mark.parametrize("url", ENDPOINTS)
def test_every_endpoint_returns_200_for_admin(client, url):
    resp = client.get(url, headers=ADMIN)
    assert resp.status_code == 200, resp.text
    assert "x-request-id" in resp.headers


@pytest.mark.parametrize("url", ENDPOINTS)
def test_every_endpoint_requires_auth(client, url):
    assert client.get(url).status_code == 401


def test_attendance_payload_is_camel_case(client):
    body = client.get(
        "/analytics/attendance", params={"from": "2026-03-09", "to": "2026-03-15"}, headers=ADMIN
    ).json()
    assert body["meta"]["from"] == "2026-03-09" and body["meta"]["to"] == "2026-03-15"
    assert body["meta"]["timezone"] == "Asia/Kolkata"
    assert body["summary"]["totalVisits"] == 3
    assert body["summary"]["avgDurationMinutes"] == 45.0  # 60 and 30 min; the Dubai visit was auto-closed
    assert set(body["series"][0]) == {"period", "visits", "uniqueMembers", "avgDurationMinutes"}
    assert body["byWeekday"][0]["isoDay"] == 1


def test_default_range_is_last_90_days(client):
    meta = client.get("/analytics/revenue", headers=ADMIN).json()["meta"]
    assert (meta["from"], meta["to"], meta["granularity"]) == ("2025-12-16", "2026-03-15", "day")


def test_revenue_endpoint(client):
    body = client.get(
        "/analytics/revenue",
        params={"from": "2026-02-01", "to": "2026-03-15", "granularity": "month"},
        headers=ADMIN,
    ).json()
    assert body["summary"]["gross"] == 4700.0
    assert body["summary"]["refunds"] == 200.0
    assert body["summary"]["net"] == 4500.0
    assert body["summary"]["failedPaymentRate"] == 0.25
    assert [p["period"] for p in body["series"]] == ["2026-02-01", "2026-03-01"]
    assert {m["method"] for m in body["paymentMethods"]} == {"UPI", "CARD", "CASH"}


def test_peak_hours_occupancy_only_for_single_branch(client):
    params = {"from": "2026-03-09", "to": "2026-03-15"}
    assert (
        client.get("/analytics/attendance/peak-hours", params=params, headers=ADMIN).json()["occupancy"]
        is None
    )
    body = client.get(
        "/analytics/attendance/peak-hours", params={**params, "branchId": 1}, headers=ADMIN
    ).json()
    assert body["occupancy"]["capacity"] == 50
    assert len(body["occupancy"]["hours"]) == 24
    assert len(body["heatmap"]) == 7 and len(body["heatmap"][0]["avgCheckIns"]) == 24


def test_members_endpoint(client):
    body = client.get(
        "/analytics/members",
        params={"from": "2026-01-01", "granularity": "month", "cohortMonths": 3},
        headers=ADMIN,
    ).json()
    assert body["summary"]["activeMembers"] == 3
    assert [p["newMembers"] for p in body["series"]] == [1, 1, 1]
    assert [c["cohort"] for c in body["cohorts"]] == ["2026-01", "2026-02", "2026-03"]
    assert {e["segment"] for e in body["engagement"]} == {"HIGHLY_ENGAGED", "REGULAR", "LOW", "INACTIVE"}


def test_churn_risk_endpoint(client):
    body = client.get("/analytics/members/churn-risk", params={"size": 2}, headers=ADMIN).json()
    assert body["scoredMembers"] == 3
    assert body["size"] == 2 and body["totalPages"] == 2
    first = body["items"][0]
    # member 1: subscription ends in 5 days, failed payment, frozen once -> 25 + 15 + 10
    assert first["memberCode"] == "M1001" and first["score"] == 50 and first["band"] == "MEDIUM"
    assert {r["code"] for r in first["reasons"]} == {
        "EXPIRING_WITHOUT_RENEWAL",
        "RECENT_FAILED_PAYMENT",
        "FROZEN_THIS_TERM",
    }
    assert first["subscriptionEndDate"] == "2026-03-20"
    assert first["lastVisit"].startswith("2026-03-10")
    assert {r["code"] for r in body["rules"]} >= {"NO_VISIT_30_DAYS", "OPEN_COMPLAINT"}

    high_only = client.get("/analytics/members/churn-risk", params={"minBand": "HIGH"}, headers=ADMIN).json()
    assert high_only["items"] == [] and high_only["totalItems"] == 0


def test_churn_risk_validation(client):
    assert client.get("/analytics/members/churn-risk", params={"size": 201}, headers=ADMIN).status_code == 400
    assert (
        client.get("/analytics/members/churn-risk", params={"minBand": "x"}, headers=ADMIN).status_code == 400
    )
    assert client.get("/analytics/members/churn-risk", params={"page": -1}, headers=ADMIN).status_code == 400


def test_memberships_churn_contract(client):
    resp = client.get("/analytics/memberships/churn", params={"month": "2026-03"}, headers=ADMIN)
    assert resp.status_code == 200
    assert resp.json() == {
        "branchId": None,
        "month": "2026-03",
        "churnRate": 0.0,
        "churnedMembers": 0,
        "membersAtStart": 3,
        "renewalRate": None,
    }


def test_memberships_churn_defaults_to_current_month_and_branch(client):
    resp = client.get("/analytics/memberships/churn", params={"branchId": 2}, headers=auth(["MANAGER"], [2]))
    assert resp.json() == {
        "branchId": 2,
        "month": "2026-03",
        "churnRate": 0.0,
        "churnedMembers": 0,
        "membersAtStart": 1,
        "renewalRate": None,
    }


@pytest.mark.parametrize("month", ["2026-13", "26-03", "March", "2026-04"])
def test_memberships_churn_invalid_month(client, month):
    resp = client.get("/analytics/memberships/churn", params={"month": month}, headers=ADMIN)
    assert resp.status_code == 400 and resp.json()["code"] == "VALIDATION_FAILED"


def test_memberships_endpoint(client):
    body = client.get("/analytics/memberships", params={"from": "2026-02-01"}, headers=ADMIN).json()
    assert [m["month"] for m in body["monthly"]] == ["2026-02", "2026-03"]
    assert body["monthly"][1]["membersAtStart"] == 3
    assert sum(p["activeSubscriptions"] for p in body["planMix"]) == 3
    assert [w["withinDays"] for w in body["upcomingExpiries"]] == [30, 60, 90]
    assert body["upcomingExpiries"][0]["expiring"] == 2


def test_trainers_endpoint(client):
    body = client.get(
        "/analytics/trainers", params={"from": "2026-03-02", "to": "2026-03-15"}, headers=ADMIN
    ).json()
    (t,) = body["trainers"]
    assert t["name"] == "Tara Coach"
    assert t["availableHours"] == 8.0 and t["bookedHours"] == 1.0 and t["utilisationPct"] == 12.5
    assert t["completionRate"] == 1.0 and t["activeClients"] == 1 and t["avgClientVisits"] == 1.0


def test_branches_endpoint(client):
    body = client.get(
        "/analytics/branches", params={"from": "2026-02-01", "to": "2026-03-15"}, headers=ADMIN
    ).json()
    ranks = [(b["branchId"], b["revenueRank"], b["netRevenue"]) for b in body["branches"]]
    assert ranks == [(1, 1, 3500.0), (2, 2, 1000.0)]
    assert body["branches"][0]["openComplaints"] == 1


@pytest.mark.parametrize(
    "params",
    [
        {"from": "2026-03-10", "to": "2026-03-01"},
        {"from": "2023-01-01", "to": "2026-03-01"},
        {"granularity": "hour"},
        {"from": "not-a-date"},
        {"branchId": 0},
    ],
)
def test_validation_errors(client, params):
    resp = client.get("/analytics/attendance", params=params, headers=ADMIN)
    assert resp.status_code == 400
    body = resp.json()
    assert body["code"] == "VALIDATION_FAILED" and body["error"] == "Bad Request"


def test_max_range_730_days_allowed(client):
    resp = client.get(
        "/analytics/attendance",
        params={"from": "2024-03-15", "to": "2026-03-15", "granularity": "month"},
        headers=ADMIN,
    )
    assert resp.status_code == 200


def test_responses_are_cached_per_scope(client, recorder):
    client.get("/analytics/revenue", headers=ADMIN)
    client.get("/analytics/revenue", headers=ADMIN)
    assert len(recorder.calls) == 2  # payments + refunds fetched once
    client.get("/analytics/revenue", headers=auth(["MANAGER"], [1]))
    assert len(recorder.calls) == 4  # different scope -> separate cache entry
    client.get("/analytics/revenue", params={"granularity": "week"}, headers=ADMIN)
    assert len(recorder.calls) == 6


def test_churn_risk_pages_share_one_cached_scoring(client, recorder):
    client.get("/analytics/members/churn-risk", params={"page": 0, "size": 1}, headers=ADMIN)
    n = len(recorder.calls)
    client.get("/analytics/members/churn-risk", params={"page": 1, "size": 1}, headers=ADMIN)
    assert len(recorder.calls) == n


def test_database_unavailable_returns_503(client):
    class DownRepo(FakeRevenueRepository):
        def fetch_payments(self, *args):
            raise OperationalError("SELECT 1", {}, Exception("connection refused"))

    app.dependency_overrides[deps.get_revenue_repository] = lambda: DownRepo(None)
    resp = client.get("/analytics/revenue", headers=ADMIN)
    assert resp.status_code == 503
    body = resp.json()
    assert body["code"] == "ANALYTICS_UNAVAILABLE" and body["error"] == "Service Unavailable"


def test_unexpected_error_returns_500_json(client):
    class BrokenRepo(FakeRevenueRepository):
        def fetch_payments(self, *args):
            raise RuntimeError("boom")

    app.dependency_overrides[deps.get_revenue_repository] = lambda: BrokenRepo(None)
    resp = client.get("/analytics/revenue", headers={**ADMIN, "X-Request-Id": "req-123"})
    assert resp.status_code == 500
    body = resp.json()
    assert body["code"] == "INTERNAL_ERROR" and "boom" not in body["message"]
    assert body["correlationId"] == "req-123"
    assert resp.headers["x-request-id"] == "req-123"


def test_correlation_id_is_echoed(client):
    resp = client.get("/analytics/attendance", headers={"X-Correlation-Id": "abc-1"})
    assert resp.headers["x-request-id"] == "abc-1"
    assert resp.json()["correlationId"] == "abc-1"
    generated = client.get("/health").headers["x-request-id"]
    assert len(generated) == 36


def test_unknown_route_uses_error_shape(client):
    resp = client.get("/analytics/nope", headers=ADMIN)
    assert resp.status_code == 404 and resp.json()["code"] == "NOT_FOUND"


def test_health_and_readiness(client):
    assert client.get("/health").json() == {"status": "UP"}
    assert client.get("/health/ready").json() == {"status": "UP", "checks": {"database": "UP"}}

    def down():
        raise OperationalError("SELECT 1", {}, Exception("down"))

    app.dependency_overrides[deps.get_db_check] = lambda: down
    resp = client.get("/health/ready")
    assert resp.status_code == 503 and resp.json()["status"] == "DOWN"


def test_metrics_exposed(client):
    client.get("/analytics/attendance", headers=ADMIN)
    text = client.get("/metrics").text
    assert "http_requests_total" in text or "http_request_duration" in text
    assert "gymops_analytics_cache_events_total" in text


def test_openapi_documents_all_endpoints(client):
    paths = client.get("/openapi.json").json()["paths"]
    for url in [*ENDPOINTS, "/health", "/health/ready"]:
        assert url in paths
    params = {p["name"] for p in paths["/analytics/revenue"]["get"]["parameters"]}
    assert {"branchId", "from", "to", "granularity"} <= params
