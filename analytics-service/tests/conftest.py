"""Shared fixtures: test settings, JWT minting, fake repositories and a TestClient.

No real database is needed: repository dependencies are overridden with in-memory fakes that
return hand-built DataFrames and record the branch scope they were called with.
"""

from __future__ import annotations

import os
import time
from collections.abc import Callable, Iterator
from datetime import date

import jwt
import pandas as pd
import pytest

TEST_SECRET = "test-secret-for-gymops-analytics-0123456789"  # >= 32 bytes
TEST_ISSUER = "gymops-core"
TEST_AUDIENCE = "gymops-api"
TODAY = date(2026, 3, 15)  # fixed "business today" for API tests (a Sunday)

os.environ["JWT_SECRET"] = TEST_SECRET
os.environ["JWT_ISSUER"] = TEST_ISSUER
os.environ["DATABASE_URL"] = "postgresql+psycopg://nobody:nothing@127.0.0.1:1/none"
os.environ["CACHE_TTL_SECONDS"] = "300"
os.environ["LOG_LEVEL"] = "WARNING"
os.environ["BUSINESS_TIMEZONE"] = "Asia/Kolkata"

from fastapi.testclient import TestClient  # noqa: E402

from app.api import deps  # noqa: E402
from app.core.cache import reset_cache  # noqa: E402
from app.main import app  # noqa: E402


def mint_token(
    roles: list[str] | None = None,
    branches: list[int] | None = None,
    *,
    typ: str = "access",
    secret: str = TEST_SECRET,
    issuer: str = TEST_ISSUER,
    audience: str = TEST_AUDIENCE,
    expires_in: int = 900,
    sub: str = "42",
    algorithm: str = "HS256",
) -> str:
    now = int(time.time())
    claims = {
        "sub": sub,
        "email": "user@example.com",
        "roles": roles if roles is not None else ["GYM_ADMIN"],
        "branches": branches if branches is not None else [],
        "typ": typ,
        "iss": issuer,
        "aud": audience,
        "iat": now,
        "exp": now + expires_in,
    }
    return jwt.encode(claims, secret, algorithm=algorithm)


def auth(roles: list[str] | None = None, branches: list[int] | None = None, **kw) -> dict[str, str]:
    return {"Authorization": f"Bearer {mint_token(roles, branches, **kw)}"}


# --------------------------------------------------------------------------------------------
# Fake repositories
# --------------------------------------------------------------------------------------------
def _ts(value: str) -> pd.Timestamp:
    return pd.Timestamp(value, tz="UTC")


def _scope(df: pd.DataFrame, column: str, branch_ids) -> pd.DataFrame:
    return df if branch_ids is None else df[df[column].isin(list(branch_ids))].reset_index(drop=True)


class Recorder:
    def __init__(self) -> None:
        self.calls: list[tuple[str, object]] = []

    def record(self, name: str, branch_ids) -> None:
        self.calls.append((name, None if branch_ids is None else tuple(branch_ids)))


BRANCHES = pd.DataFrame(
    [
        {
            "branch_id": 1,
            "code": "BLR",
            "name": "Bengaluru",
            "capacity": 50,
            "timezone": "Asia/Kolkata",
            "status": "ACTIVE",
        },
        {
            "branch_id": 2,
            "code": "DXB",
            "name": "Dubai",
            "capacity": 20,
            "timezone": "Asia/Dubai",
            "status": "ACTIVE",
        },
    ]
)

MEMBERS = pd.DataFrame(
    [
        {
            "member_id": 1,
            "member_code": "M1001",
            "first_name": "Asha",
            "last_name": "Rao",
            "home_branch_id": 1,
            "branch_name": "Bengaluru",
            "status": "ACTIVE",
            "registered_at": _ts("2026-01-10T05:00:00"),
        },
        {
            "member_id": 2,
            "member_code": "M1002",
            "first_name": "Ben",
            "last_name": "Ali",
            "home_branch_id": 1,
            "branch_name": "Bengaluru",
            "status": "ACTIVE",
            "registered_at": _ts("2026-02-20T05:00:00"),
        },
        {
            "member_id": 3,
            "member_code": "M1003",
            "first_name": "Chen",
            "last_name": "Li",
            "home_branch_id": 2,
            "branch_name": "Dubai",
            "status": "ACTIVE",
            "registered_at": _ts("2026-03-01T05:00:00"),
        },
    ]
)

SUBSCRIPTIONS = pd.DataFrame(
    [
        # member 1: monthly plan, ACTIVE, ends in 5 days with no renewal; frozen once
        {
            "subscription_id": 10,
            "member_id": 1,
            "home_branch_id": 1,
            "branch_id": 1,
            "plan_id": 100,
            "plan_code": "MONTHLY",
            "plan_name": "Monthly",
            "grace_period_days": 3,
            "previous_subscription_id": None,
            "status": "ACTIVE",
            "change_type": "NEW",
            "start_date": date(2026, 2, 20),
            "end_date": date(2026, 3, 20),
            "activated_at": _ts("2026-02-20T05:00:00"),
            "cancelled_at": None,
            "frozen_from": None,
            "frozen_until": None,
            "freeze_days_used": 4,
            "freeze_count": 1,
        },
        # member 2: ACTIVE quarterly
        {
            "subscription_id": 20,
            "member_id": 2,
            "home_branch_id": 1,
            "branch_id": 1,
            "plan_id": 200,
            "plan_code": "QUARTERLY",
            "plan_name": "Quarterly",
            "grace_period_days": 5,
            "previous_subscription_id": None,
            "status": "ACTIVE",
            "change_type": "NEW",
            "start_date": date(2026, 2, 20),
            "end_date": date(2026, 5, 19),
            "activated_at": _ts("2026-02-20T05:00:00"),
            "cancelled_at": None,
            "frozen_from": None,
            "frozen_until": None,
            "freeze_days_used": 0,
            "freeze_count": 0,
        },
        # member 3 (branch 2): ACTIVE monthly
        {
            "subscription_id": 30,
            "member_id": 3,
            "home_branch_id": 2,
            "branch_id": 2,
            "plan_id": 100,
            "plan_code": "MONTHLY",
            "plan_name": "Monthly",
            "grace_period_days": 3,
            "previous_subscription_id": None,
            "status": "ACTIVE",
            "change_type": "NEW",
            "start_date": date(2026, 3, 1),
            "end_date": date(2026, 3, 31),
            "activated_at": _ts("2026-03-01T05:00:00"),
            "cancelled_at": None,
            "frozen_from": None,
            "frozen_until": None,
            "freeze_days_used": 0,
            "freeze_count": 0,
        },
    ]
)

VISITS = pd.DataFrame(
    [
        {
            "id": 1,
            "member_id": 1,
            "branch_id": 1,
            "check_in_time": _ts("2026-03-10T01:00:00"),
            "check_out_time": _ts("2026-03-10T02:00:00"),
            "auto_checked_out": False,
            "timezone": "Asia/Kolkata",
        },
        {
            "id": 2,
            "member_id": 2,
            "branch_id": 1,
            "check_in_time": _ts("2026-03-11T12:30:00"),
            "check_out_time": _ts("2026-03-11T13:00:00"),
            "auto_checked_out": False,
            "timezone": "Asia/Kolkata",
        },
        {
            "id": 3,
            "member_id": 3,
            "branch_id": 2,
            "check_in_time": _ts("2026-03-12T14:00:00"),
            "check_out_time": _ts("2026-03-12T20:00:00"),
            "auto_checked_out": True,
            "timezone": "Asia/Dubai",
        },
    ]
)

PAYMENTS = pd.DataFrame(
    [
        {
            "payment_id": 1,
            "member_id": 1,
            "amount": 1000.0,
            "refunded_amount": 200.0,
            "method": "UPI",
            "status": "PARTIALLY_REFUNDED",
            "processed_at": _ts("2026-02-20T06:00:00"),
            "branch_id": 1,
            "branch_name": "Bengaluru",
            "plan_id": 100,
            "plan_name": "Monthly",
        },
        {
            "payment_id": 2,
            "member_id": 2,
            "amount": 2700.0,
            "refunded_amount": 0.0,
            "method": "CARD",
            "status": "SUCCEEDED",
            "processed_at": _ts("2026-02-20T07:00:00"),
            "branch_id": 1,
            "branch_name": "Bengaluru",
            "plan_id": 200,
            "plan_name": "Quarterly",
        },
        {
            "payment_id": 3,
            "member_id": 3,
            "amount": 1000.0,
            "refunded_amount": 0.0,
            "method": "CASH",
            "status": "SUCCEEDED",
            "processed_at": _ts("2026-03-01T06:00:00"),
            "branch_id": 2,
            "branch_name": "Dubai",
            "plan_id": 100,
            "plan_name": "Monthly",
        },
        {
            "payment_id": 4,
            "member_id": 1,
            "amount": 1000.0,
            "refunded_amount": 0.0,
            "method": "CARD",
            "status": "FAILED",
            "processed_at": _ts("2026-03-05T06:00:00"),
            "branch_id": 1,
            "branch_name": "Bengaluru",
            "plan_id": 100,
            "plan_name": "Monthly",
        },
    ]
)

REFUNDS = pd.DataFrame(
    [
        {
            "refund_id": 1,
            "payment_id": 1,
            "amount": 200.0,
            "processed_at": _ts("2026-03-02T06:00:00"),
            "branch_id": 1,
            "branch_name": "Bengaluru",
            "plan_id": 100,
            "plan_name": "Monthly",
        },
    ]
)

COMPLAINTS = pd.DataFrame(
    [
        {
            "complaint_id": 1,
            "branch_id": 1,
            "member_id": 2,
            "status": "OPEN",
            "created_at": _ts("2026-03-01T06:00:00"),
        },
    ]
)


class FakeAttendanceRepository:
    def __init__(self, rec: Recorder) -> None:
        self.rec = rec

    def fetch_visits(self, start, end, branch_ids):
        self.rec.record("attendance.fetch_visits", branch_ids)
        return _scope(VISITS, "branch_id", branch_ids)


class FakeBranchRepository:
    def __init__(self, rec: Recorder) -> None:
        self.rec = rec

    def fetch_branches(self, branch_ids):
        self.rec.record("branches.fetch_branches", branch_ids)
        return _scope(BRANCHES, "branch_id", branch_ids)

    def fetch_open_complaints(self, branch_ids):
        self.rec.record("branches.fetch_open_complaints", branch_ids)
        return _scope(COMPLAINTS, "branch_id", branch_ids)


class FakeMemberRepository:
    def __init__(self, rec: Recorder) -> None:
        self.rec = rec

    def fetch_members(self, branch_ids):
        self.rec.record("members.fetch_members", branch_ids)
        return _scope(MEMBERS, "home_branch_id", branch_ids)

    def fetch_subscriptions(self, branch_ids):
        self.rec.record("members.fetch_subscriptions", branch_ids)
        return _scope(SUBSCRIPTIONS, "home_branch_id", branch_ids)

    def _member_ids(self, branch_ids):
        return set(_scope(MEMBERS, "home_branch_id", branch_ids)["member_id"])

    def fetch_member_visits(self, since, branch_ids):
        self.rec.record("members.fetch_member_visits", branch_ids)
        v = VISITS[VISITS["member_id"].isin(self._member_ids(branch_ids))]
        return v[["member_id", "check_in_time"]].reset_index(drop=True)

    def fetch_last_visits(self, branch_ids):
        self.rec.record("members.fetch_last_visits", branch_ids)
        v = VISITS[VISITS["member_id"].isin(self._member_ids(branch_ids))]
        return (
            v.groupby("member_id", as_index=False)["check_in_time"]
            .max()
            .rename(columns={"check_in_time": "last_check_in"})
        )

    def fetch_failed_payments(self, since, branch_ids):
        self.rec.record("members.fetch_failed_payments", branch_ids)
        p = PAYMENTS[
            (PAYMENTS["status"] == "FAILED") & PAYMENTS["member_id"].isin(self._member_ids(branch_ids))
        ]
        return p[["payment_id", "member_id", "processed_at"]].reset_index(drop=True)

    def fetch_open_member_complaints(self, since, branch_ids):
        self.rec.record("members.fetch_open_member_complaints", branch_ids)
        c = COMPLAINTS[COMPLAINTS["member_id"].isin(self._member_ids(branch_ids))]
        return c[["complaint_id", "member_id", "status", "created_at"]].reset_index(drop=True)


class FakeRevenueRepository:
    def __init__(self, rec: Recorder) -> None:
        self.rec = rec

    def fetch_payments(self, start, end, branch_ids):
        self.rec.record("revenue.fetch_payments", branch_ids)
        return _scope(PAYMENTS, "branch_id", branch_ids)

    def fetch_refunds(self, start, end, branch_ids):
        self.rec.record("revenue.fetch_refunds", branch_ids)
        return _scope(REFUNDS, "branch_id", branch_ids)


TRAINERS = pd.DataFrame(
    [
        {
            "trainer_id": 7,
            "full_name": "Tara Coach",
            "employee_code": "T-7",
            "home_branch_id": 1,
            "branch_name": "Bengaluru",
            "status": "ACTIVE",
            "max_active_clients": 20,
        },
    ]
)


class FakeTrainerRepository:
    def __init__(self, rec: Recorder) -> None:
        self.rec = rec

    def fetch_trainers(self, branch_ids):
        self.rec.record("trainers.fetch_trainers", branch_ids)
        return _scope(TRAINERS, "home_branch_id", branch_ids)

    def fetch_availability(self, branch_ids):
        self.rec.record("trainers.fetch_availability", branch_ids)
        return pd.DataFrame(
            [
                {
                    "trainer_id": 7,
                    "branch_id": 1,
                    "day_of_week": 1,
                    "start_time": "06:00:00",
                    "end_time": "10:00:00",
                }
            ]
        )

    def fetch_sessions(self, start, end, branch_ids):
        self.rec.record("trainers.fetch_sessions", branch_ids)
        return pd.DataFrame(
            [
                {
                    "session_id": 1,
                    "trainer_id": 7,
                    "member_id": 1,
                    "branch_id": 1,
                    "starts_at": _ts("2026-03-09T01:00:00"),
                    "ends_at": _ts("2026-03-09T02:00:00"),
                    "status": "COMPLETED",
                }
            ]
        )

    def fetch_active_assignments(self, branch_ids):
        self.rec.record("trainers.fetch_active_assignments", branch_ids)
        return pd.DataFrame([{"trainer_id": 7, "member_id": 1, "assigned_at": _ts("2026-02-01T00:00:00")}])

    def fetch_client_visits(self, start, end, branch_ids):
        self.rec.record("trainers.fetch_client_visits", branch_ids)
        return VISITS[VISITS["member_id"] == 1][["member_id", "check_in_time"]].reset_index(drop=True)


@pytest.fixture
def recorder() -> Recorder:
    return Recorder()


@pytest.fixture
def client(recorder: Recorder, monkeypatch: pytest.MonkeyPatch) -> Iterator[TestClient]:
    reset_cache()
    monkeypatch.setattr(deps, "business_today", lambda settings: TODAY)
    overrides: dict[Callable, Callable] = {
        deps.get_attendance_repository: lambda: FakeAttendanceRepository(recorder),
        deps.get_branch_repository: lambda: FakeBranchRepository(recorder),
        deps.get_member_repository: lambda: FakeMemberRepository(recorder),
        deps.get_revenue_repository: lambda: FakeRevenueRepository(recorder),
        deps.get_trainer_repository: lambda: FakeTrainerRepository(recorder),
        deps.get_db_check: lambda: lambda: None,
    }
    app.dependency_overrides.update(overrides)
    with TestClient(app) as c:
        yield c
    app.dependency_overrides.clear()
    reset_cache()
