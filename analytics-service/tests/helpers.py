"""Tiny builders for hand-made DataFrames used by service tests."""

from __future__ import annotations

from datetime import date, datetime
from zoneinfo import ZoneInfo

import pandas as pd

IST = "Asia/Kolkata"


def utc(value: str) -> pd.Timestamp:
    return pd.Timestamp(value, tz="UTC")


def local_to_utc(value: str, tz: str = IST) -> pd.Timestamp:
    """'2026-03-02 18:10' wall-clock in ``tz`` -> UTC timestamp."""
    return pd.Timestamp(datetime.fromisoformat(value).replace(tzinfo=ZoneInfo(tz))).tz_convert("UTC")


def sub(
    sid: int,
    member: int,
    status: str,
    start: date,
    end: date,
    *,
    plan: int = 1,
    plan_name: str = "Monthly",
    grace: int = 0,
    prev: int | None = None,
    activated: bool = True,
    cancelled_at: pd.Timestamp | None = None,
    change_type: str = "NEW",
    freeze_count: int = 0,
    freeze_days: int = 0,
    frozen_from: date | None = None,
    frozen_until: date | None = None,
    branch: int = 1,
) -> dict:
    return {
        "subscription_id": sid,
        "member_id": member,
        "home_branch_id": branch,
        "branch_id": branch,
        "plan_id": plan,
        "plan_code": plan_name.upper(),
        "plan_name": plan_name,
        "grace_period_days": grace,
        "previous_subscription_id": prev,
        "status": status,
        "change_type": change_type,
        "start_date": start,
        "end_date": end,
        "activated_at": utc(f"{start.isoformat()}T04:00:00") if activated else None,
        "cancelled_at": cancelled_at,
        "frozen_from": frozen_from,
        "frozen_until": frozen_until,
        "freeze_days_used": freeze_days,
        "freeze_count": freeze_count,
    }


def member(mid: int, registered_at: pd.Timestamp, branch: int = 1, name: str = "Test Member") -> dict:
    first, last = name.split(" ", 1)
    return {
        "member_id": mid,
        "member_code": f"M{mid:04d}",
        "first_name": first,
        "last_name": last,
        "home_branch_id": branch,
        "branch_name": f"Branch {branch}",
        "status": "ACTIVE",
        "registered_at": registered_at,
    }


def frame(rows: list[dict], columns: tuple[str, ...] | list[str] | None = None) -> pd.DataFrame:
    if not rows:
        return pd.DataFrame(columns=list(columns or []))
    return pd.DataFrame(rows)
