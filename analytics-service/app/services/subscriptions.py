"""Subscription normalisation shared by member, membership, churn-risk and branch analytics.

Terminology
-----------
* **Committed** subscription: status SCHEDULED, ACTIVE, FROZEN, GRACE, EXPIRED or SUPERSEDED, plus
  CANCELLED subscriptions that were activated (``activated_at`` not null). PENDING_PAYMENT never
  granted access and is ignored everywhere.
* **Effective end** (last day of access, inclusive):
  - CANCELLED (activated): min(end_date, local date of cancelled_at)
  - SUPERSEDED: min(end_date, start_date of its successor - 1 day) when a successor links to it via
    ``previous_subscription_id`` (upgrades/downgrades cut the old term short)
  - otherwise: end_date
* **Current access** (as of ``today``): status ACTIVE/FROZEN with start_date <= today <= end_date,
  or status GRACE with start_date <= today (grace runs past end_date by definition).
"""

from __future__ import annotations

from datetime import date

import pandas as pd

from app.services.common import ACCESS_STATUSES, COMMITTED_STATUSES, ensure_columns, to_dates, to_local

SUBSCRIPTION_COLUMNS = (
    "subscription_id",
    "member_id",
    "home_branch_id",
    "branch_id",
    "plan_id",
    "plan_code",
    "plan_name",
    "grace_period_days",
    "previous_subscription_id",
    "status",
    "change_type",
    "start_date",
    "end_date",
    "activated_at",
    "cancelled_at",
    "frozen_from",
    "frozen_until",
    "freeze_days_used",
    "freeze_count",
)


def _earliest(primary: pd.Series, other: pd.Series) -> pd.Series:
    """Element-wise minimum of two date columns, ignoring missing values in ``other``."""
    return primary.where(other.isna() | (primary <= other), other)


def prepare_subscriptions(subs: pd.DataFrame, tz: str) -> pd.DataFrame:
    df = ensure_columns(subs, SUBSCRIPTION_COLUMNS)
    df["start"] = to_dates(df["start_date"])
    df["end"] = to_dates(df["end_date"])
    df["frozen_from"] = to_dates(df["frozen_from"])
    df["frozen_until"] = to_dates(df["frozen_until"])
    df["activated_at"] = pd.to_datetime(df["activated_at"], utc=True)
    cancelled_at = pd.to_datetime(df["cancelled_at"], utc=True)
    df["cancelled_date"] = to_local(cancelled_at, tz).dt.normalize()
    for col in ("grace_period_days", "freeze_days_used", "freeze_count"):
        df[col] = pd.to_numeric(df[col], errors="coerce").fillna(0).astype(int)
    df["previous_subscription_id"] = pd.to_numeric(df["previous_subscription_id"], errors="coerce")

    df["committed"] = df["status"].isin(COMMITTED_STATUSES) | (
        df["status"].eq("CANCELLED") & df["activated_at"].notna()
    )

    eff_end = df["end"].copy()
    cancelled = df["status"].eq("CANCELLED") & df["cancelled_date"].notna()
    eff_end = eff_end.where(~cancelled, _earliest(df["end"], df["cancelled_date"]))

    successor_start = (
        df[df["committed"] & df["previous_subscription_id"].notna()]
        .groupby("previous_subscription_id")["start"]
        .min()
    )
    succ = pd.to_datetime(df["subscription_id"].map(successor_start.to_dict())).astype("datetime64[ns]")
    superseded = df["status"].eq("SUPERSEDED") & succ.notna()
    cut = succ - pd.Timedelta(days=1)
    eff_end = eff_end.where(~superseded, _earliest(df["end"], cut))
    df["effective_end"] = eff_end.astype("datetime64[ns]")
    return df


def current_access(subs: pd.DataFrame, today: date) -> pd.DataFrame:
    """Subscriptions granting access on ``today`` (expects a prepared frame)."""
    t = pd.Timestamp(today)
    started = subs["status"].isin(ACCESS_STATUSES) & (subs["start"] <= t)
    return subs[started & ((subs["end"] >= t) | subs["status"].eq("GRACE"))]


def has_successor(subs: pd.DataFrame, candidates: pd.DataFrame) -> pd.Series:
    """True for each candidate subscription that already has a committed follow-on subscription:
    either linked through ``previous_subscription_id`` or any later-starting committed subscription
    of the same member (a renewal booked as NEW still counts)."""
    if candidates.empty:
        return pd.Series(dtype=bool, index=candidates.index)
    committed = subs[subs["committed"]]
    linked = candidates["subscription_id"].isin(committed["previous_subscription_id"].dropna())
    later = (
        candidates[["subscription_id", "member_id", "start"]]
        .reset_index()
        .merge(committed[["member_id", "subscription_id", "start"]], on="member_id", suffixes=("", "_next"))
    )
    later = later[
        (later["subscription_id_next"] != later["subscription_id"]) & (later["start_next"] > later["start"])
    ]
    later_ids = set(later["index"])
    return linked | pd.Series(candidates.index.isin(later_ids), index=candidates.index)
