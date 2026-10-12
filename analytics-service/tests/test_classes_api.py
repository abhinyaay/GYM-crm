import pandas as pd
import pytest

from app.api.routers.classes import get_classes_repository
from app.main import app
from tests.conftest import Recorder, _scope, _ts, auth

ADMIN = auth(["GYM_ADMIN"])
URL = "/analytics/classes"

SESSIONS = pd.DataFrame(
    [
        {
            "session_id": 1,
            "class_type_id": 1,
            "branch_id": 1,
            "instructor_id": 7,
            "starts_at": _ts("2026-03-09T01:00:00"),  # Mon 06:30 IST
            "ends_at": _ts("2026-03-09T02:00:00"),
            "capacity": 2,
            "status": "COMPLETED",
        },
        {
            "session_id": 2,
            "class_type_id": 1,
            "branch_id": 2,
            "instructor_id": 8,
            "starts_at": _ts("2026-03-10T14:00:00"),
            "ends_at": _ts("2026-03-10T15:00:00"),
            "capacity": 10,
            "status": "SCHEDULED",
        },
    ]
)
BOOKINGS = pd.DataFrame(
    [
        {"booking_id": 1, "session_id": 1, "status": "ATTENDED", "promoted_at": None},
        {"booking_id": 2, "session_id": 1, "status": "NO_SHOW", "promoted_at": None},
        {"booking_id": 3, "session_id": 1, "status": "WAITLISTED", "promoted_at": None},
        {"booking_id": 4, "session_id": 2, "status": "BOOKED", "promoted_at": None},
    ]
)


class FakeClassesRepository:
    def __init__(self, rec: Recorder) -> None:
        self.rec = rec

    def fetch_class_types(self):
        self.rec.record("classes.fetch_class_types", None)
        return pd.DataFrame([{"class_type_id": 1, "name": "Sunrise Yoga", "category": "YOGA"}])

    def fetch_sessions(self, start, end, branch_ids):
        self.rec.record("classes.fetch_sessions", branch_ids)
        return _scope(SESSIONS, "branch_id", branch_ids)

    def fetch_bookings(self, start, end, branch_ids):
        self.rec.record("classes.fetch_bookings", branch_ids)
        ids = set(_scope(SESSIONS, "branch_id", branch_ids)["session_id"])
        return BOOKINGS[BOOKINGS["session_id"].isin(ids)].reset_index(drop=True)

    def fetch_instructors(self, start, end, branch_ids):
        self.rec.record("classes.fetch_instructors", branch_ids)
        return pd.DataFrame(
            [{"trainer_id": 7, "full_name": "Tara Coach"}, {"trainer_id": 8, "full_name": "Vik Coach"}]
        )


@pytest.fixture
def classes_client(client, recorder):
    # `client` clears app.dependency_overrides on teardown
    app.dependency_overrides[get_classes_repository] = lambda: FakeClassesRepository(recorder)
    return client


def test_classes_endpoint_shape(classes_client):
    resp = classes_client.get(URL, params={"from": "2026-03-09", "to": "2026-03-15"}, headers=ADMIN)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert set(body) == {"meta", "summary", "byClassType", "timeSlots", "instructors", "daily"}
    assert body["meta"]["from"] == "2026-03-09" and body["meta"]["timezone"] == "Asia/Kolkata"
    assert set(body["summary"]) == {
        "sessions",
        "cancelledSessions",
        "capacity",
        "occupied",
        "fillRate",
        "attended",
        "noShows",
        "attendanceRate",
        "lateCancels",
        "lateCancelRate",
        "waitlistJoins",
        "waitlistPromotions",
        "sessionsFull",
        "avgOccupiedPerSession",
    }
    s = body["summary"]
    assert (s["sessions"], s["capacity"], s["occupied"]) == (2, 12, 3)
    assert s["fillRate"] == 0.25 and s["attendanceRate"] == 0.5 and s["sessionsFull"] == 1
    assert s["waitlistJoins"] == 1 and s["lateCancelRate"] == 0.0

    (ct,) = body["byClassType"]
    assert ct["classTypeId"] == 1 and ct["name"] == "Sunrise Yoga" and ct["occupied"] == 3
    assert body["timeSlots"][0] == {
        "weekday": 1,
        "hour": 6,
        "sessions": 1,
        "occupied": 2,
        "capacity": 2,
        "fillRate": 1.0,
        "waitlistJoins": 1,
    }
    assert [i["trainerId"] for i in body["instructors"]] == [7, 8]
    assert set(body["instructors"][0]) == {
        "trainerId",
        "name",
        "sessionsTaught",
        "occupied",
        "avgFillRate",
        "attended",
        "noShows",
        "attendanceRate",
    }
    assert len(body["daily"]) == 7
    assert body["daily"][0] == {
        "date": "2026-03-09",
        "sessions": 1,
        "occupied": 2,
        "capacity": 2,
        "attended": 1,
        "fillRate": 1.0,
    }
    assert body["daily"][-1]["sessions"] == 0 and body["daily"][-1]["fillRate"] is None


def test_classes_manager_scoped_to_assigned_branch(classes_client, recorder):
    resp = classes_client.get(URL, headers=auth(["MANAGER"], [1]))
    assert resp.status_code == 200, resp.text
    assert resp.json()["meta"]["scopeBranchIds"] == [1]
    assert ("classes.fetch_sessions", (1,)) in recorder.calls
    assert ("classes.fetch_bookings", (1,)) in recorder.calls


def test_classes_default_range_and_cache(classes_client, recorder):
    body = classes_client.get(URL, headers=ADMIN).json()
    assert body["meta"]["from"] == "2025-12-16" and body["meta"]["to"] == "2026-03-15"
    assert len(body["daily"]) == 90
    n = len(recorder.calls)
    classes_client.get(URL, headers=ADMIN)
    assert len(recorder.calls) == n  # served from cache


def test_classes_requires_auth(classes_client):
    resp = classes_client.get(URL)
    assert resp.status_code == 401 and resp.json()["code"] == "UNAUTHORIZED"


@pytest.mark.parametrize("role", ["TRAINER", "MEMBER", "RECEPTIONIST"])
def test_classes_non_analytics_roles_403(classes_client, role):
    resp = classes_client.get(URL, headers=auth([role], [1]))
    assert resp.status_code == 403 and resp.json()["code"] == "ACCESS_DENIED"


def test_classes_manager_other_branch_403(classes_client, recorder):
    resp = classes_client.get(URL, params={"branchId": 2}, headers=auth(["MANAGER"], [1]))
    assert resp.status_code == 403 and resp.json()["code"] == "ACCESS_DENIED"
    assert recorder.calls == []


@pytest.mark.parametrize(
    "params",
    [{"from": "2026-03-10", "to": "2026-03-01"}, {"from": "not-a-date"}, {"branchId": 0}],
)
def test_classes_validation_errors(classes_client, params):
    resp = classes_client.get(URL, params=params, headers=ADMIN)
    assert resp.status_code == 400 and resp.json()["code"] == "VALIDATION_FAILED"
