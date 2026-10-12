"""Member analytics (pure Pandas): registrations, active members, engagement, retention cohorts."""

from __future__ import annotations

from datetime import date, timedelta
from typing import Any

import numpy as np
import pandas as pd

from app.services.common import (
    Granularity,
    ensure_columns,
    in_date_range,
    period_floor,
    period_starts,
    rate,
    safe_div,
    to_local,
)
from app.services.subscriptions import current_access, prepare_subscriptions

MEMBER_COLUMNS = (
    "member_id",
    "member_code",
    "first_name",
    "last_name",
    "home_branch_id",
    "branch_name",
    "status",
    "registered_at",
)
MEMBER_VISIT_COLUMNS = ("member_id", "check_in_time")

ENGAGEMENT_WINDOW_DAYS = 30
# (segment, minimum visits in the last 30 days) - evaluated top-down.
ENGAGEMENT_SEGMENTS: tuple[tuple[str, int], ...] = (
    ("HIGHLY_ENGAGED", 12),
    ("REGULAR", 4),
    ("LOW", 1),
    ("INACTIVE", 0),
)


def prepare_members(members: pd.DataFrame, tz: str) -> pd.DataFrame:
    df = ensure_columns(members, MEMBER_COLUMNS)
    df["registered_local"] = to_local(df["registered_at"], tz).dt.normalize()
    return df


def visits_per_member(visits: pd.DataFrame, tz: str, first_day: date, last_day: date) -> pd.Series:
    """Number of check-ins per member with local (business tz) date in [first_day, last_day]."""
    df = ensure_columns(visits, MEMBER_VISIT_COLUMNS)
    local = to_local(df["check_in_time"], tz).dt.normalize()
    df = df[in_date_range(local, first_day, last_day)]
    return df.groupby("member_id").size()


def engagement_segment(visit_count: int) -> str:
    for name, minimum in ENGAGEMENT_SEGMENTS:
        if visit_count >= minimum:
            return name
    return "INACTIVE"


def engagement(subs_p: pd.DataFrame, visits: pd.DataFrame, today: date, tz: str) -> list[dict[str, Any]]:
    """Members with current access, segmented by visits in the last 30 days (today inclusive)."""
    active_members = pd.Index(current_access(subs_p, today)["member_id"].unique())
    counts = visits_per_member(visits, tz, today - timedelta(days=ENGAGEMENT_WINDOW_DAYS - 1), today)
    per_member = counts.reindex(active_members, fill_value=0)
    segments = per_member.map(engagement_segment).value_counts()
    total = len(active_members)
    return [
        {
            "segment": name,
            "min_visits": minimum,
            "members": int(segments.get(name, 0)),
            "share": rate(safe_div(segments.get(name, 0), total)),
        }
        for name, minimum in ENGAGEMENT_SEGMENTS
    ]


def _month_ordinal(ts: pd.Series) -> pd.Series:
    return ts.dt.year * 12 + ts.dt.month - 1


def retention_cohorts(
    members_p: pd.DataFrame, subs_p: pd.DataFrame, today: date, cohort_months: int
) -> list[dict[str, Any]]:
    """Monthly registration cohorts x months since joining -> share still holding committed access.

    A member of cohort C (registration month) is *retained* in month C+k when any of their committed
    subscriptions covers at least one day of that calendar month, i.e. [start_date, effective_end]
    overlaps the month. Rate = retained members / cohort size. Offsets run from 0 up to the current
    month. Only the last ``cohort_months`` registration months (including the current one) are shown.
    """
    current_ord = today.year * 12 + today.month - 1
    first_ord = current_ord - cohort_months + 1
    m = members_p[members_p["registered_local"].notna()].copy()
    m["cohort_ord"] = _month_ordinal(m["registered_local"])
    m = m[(m["cohort_ord"] >= first_ord) & (m["cohort_ord"] <= current_ord)]
    sizes = m.groupby("cohort_ord")["member_id"].nunique()

    c = subs_p[
        subs_p["committed"]
        & (subs_p["effective_end"] >= subs_p["start"])
        & subs_p["member_id"].isin(m["member_id"])
    ]
    covered = pd.DataFrame(columns=["member_id", "ord"])
    if not c.empty:
        s_ord = _month_ordinal(c["start"]).to_numpy()
        e_ord = np.minimum(_month_ordinal(c["effective_end"]).to_numpy(), current_ord)
        n = np.clip(e_ord - s_ord + 1, 0, None)
        rep = np.repeat(np.arange(len(c)), n)
        offs = pd.Series(rep).groupby(rep).cumcount().to_numpy()
        covered = pd.DataFrame({"member_id": c["member_id"].to_numpy()[rep], "ord": s_ord[rep] + offs})
        covered = covered.drop_duplicates()
    joined = covered.merge(m[["member_id", "cohort_ord"]], on="member_id")
    joined["offset"] = joined["ord"] - joined["cohort_ord"]
    joined = joined[(joined["offset"] >= 0) & (joined["ord"] <= current_ord)]
    retained = joined.groupby(["cohort_ord", "offset"])["member_id"].nunique()

    rows = []
    for cohort_ord in range(first_ord, current_ord + 1):
        size = int(sizes.get(cohort_ord, 0))
        year, month0 = divmod(cohort_ord, 12)
        cells = []
        for offset in range(0, current_ord - cohort_ord + 1):
            kept = int(retained.get((cohort_ord, offset), 0))
            cells.append(
                {"month_offset": offset, "retained_members": kept, "rate": rate(safe_div(kept, size))}
            )
        rows.append({"cohort": f"{year:04d}-{month0 + 1:02d}", "members": size, "retention": cells})
    return rows


def compute_members(
    members: pd.DataFrame,
    subs: pd.DataFrame,
    recent_visits: pd.DataFrame,
    date_from: date,
    date_to: date,
    granularity: Granularity | str,
    today: date,
    tz: str,
    cohort_months: int = 12,
) -> dict[str, Any]:
    """Registrations per period, active members today, engagement segments, retention cohorts.

    * ``newMembers``: members whose ``registered_at`` (business tz) falls in the period.
    * ``activeMembers``: distinct members holding a subscription with current access (see
      ``app.services.subscriptions``).
    * Engagement: members with current access, by check-ins in the last 30 days
      (HIGHLY_ENGAGED >= 12, REGULAR 4-11, LOW 1-3, INACTIVE 0).
    """
    members_p = prepare_members(members, tz)
    subs_p = prepare_subscriptions(subs, tz)

    reg = members_p[in_date_range(members_p["registered_local"], date_from, date_to)].copy()
    reg["period"] = period_floor(reg["registered_local"], granularity)
    per = (
        reg.groupby("period")
        .size()
        .reindex(pd.DatetimeIndex(period_starts(date_from, date_to, granularity)), fill_value=0)
    )
    series = [{"period": ts.date().isoformat(), "new_members": int(v)} for ts, v in per.items()]

    access = current_access(subs_p, today)
    active_ids = access["member_id"].unique()
    by_status = access.drop_duplicates("member_id")["status"].value_counts()
    summary = {
        "total_members": int(members_p["member_id"].nunique()),
        "new_members": len(reg),
        "active_members": len(active_ids),
        "active_by_status": {s: int(by_status.get(s, 0)) for s in ("ACTIVE", "FROZEN", "GRACE")},
        "members_without_access": int(
            members_p["member_id"].nunique() - len(set(active_ids) & set(members_p["member_id"]))
        ),
    }
    return {
        "series": series,
        "summary": summary,
        "engagement": engagement(subs_p, recent_visits, today, tz),
        "cohorts": retention_cohorts(members_p, subs_p, today, cohort_months),
    }
