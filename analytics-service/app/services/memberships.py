"""Membership lifecycle analytics (pure Pandas): renewal, churn, plan mix, expiries, freezes,
cancellations. See ``app.services.subscriptions`` for "committed" and "effective end".

Renewal rate (month M)
    ended(M)   = subscriptions with status ACTIVE, FROZEN, GRACE, EXPIRED or SUPERSEDED whose
                 end_date lies in M and is on or before the as-of date (CANCELLED and
                 PENDING_PAYMENT terms are not "ended naturally").
    renewed(M) = those in ended(M) referenced by a *committed* subscription's
                 previous_subscription_id (RENEWAL / UPGRADE / DOWNGRADE successors).
    renewalRate = renewed / ended  (null when nothing ended).

Churn rate (month M = [m0, m1])
    atStart(M) = distinct members holding a committed subscription with start_date <= m0 <= effective_end.
    A member in atStart(M) *churned* in M when their latest committed subscription whose effective_end
    falls in M is not continued, i.e. no other committed subscription of that member starts on or
    before effective_end + grace_period_days + 1 (plan of the ending subscription) and runs past
    effective_end.
    Members whose grace window has not closed yet as of today are "pending" and not counted as churned.
    churnRate = churned / atStart (0 when atStart is 0).
"""

from __future__ import annotations

from datetime import date
from typing import Any

import pandas as pd

from app.services.common import (
    in_date_range,
    month_end,
    month_starts,
    num,
    rate,
    safe_div,
)
from app.services.subscriptions import current_access, has_successor, prepare_subscriptions

ENDABLE_STATUSES = frozenset({"ACTIVE", "FROZEN", "GRACE", "EXPIRED", "SUPERSEDED"})
EXPIRY_WINDOWS = (30, 60, 90)


def renewal_stats(subs_p: pd.DataFrame, m0: date, m1: date, as_of: date) -> dict[str, Any]:
    t0, t1, now = pd.Timestamp(m0), pd.Timestamp(m1), pd.Timestamp(as_of)
    ended = subs_p[
        subs_p["status"].isin(ENDABLE_STATUSES)
        & (subs_p["end"] >= t0)
        & (subs_p["end"] <= t1)
        & (subs_p["end"] <= now)
    ]
    successors = subs_p.loc[subs_p["committed"], "previous_subscription_id"].dropna()
    renewed = int(ended["subscription_id"].isin(successors).sum())
    return {
        "ended_subscriptions": len(ended),
        "renewed_subscriptions": renewed,
        "renewal_rate": rate(safe_div(renewed, len(ended))),
    }


def churn_stats(subs_p: pd.DataFrame, m0: date, m1: date, as_of: date) -> dict[str, Any]:
    t0, t1, now = pd.Timestamp(m0), pd.Timestamp(m1), pd.Timestamp(as_of)
    c = subs_p[subs_p["committed"] & (subs_p["effective_end"] >= subs_p["start"])]
    at_start = set(c.loc[(c["start"] <= t0) & (c["effective_end"] >= t0), "member_id"])

    cand = c[(c["effective_end"] >= t0) & (c["effective_end"] <= t1) & c["member_id"].isin(at_start)]
    cand = cand.sort_values("effective_end").drop_duplicates("member_id", keep="last")
    churned = pending = 0
    if not cand.empty:
        pairs = cand[["subscription_id", "member_id", "effective_end", "grace_period_days"]].merge(
            c[["member_id", "subscription_id", "start", "effective_end"]],
            on="member_id",
            suffixes=("", "_next"),
        )
        limit = pairs["effective_end"] + pd.to_timedelta(pairs["grace_period_days"] + 1, unit="D")
        cont = pairs[
            (pairs["subscription_id_next"] != pairs["subscription_id"])
            & (pairs["start"] <= limit)
            & (pairs["effective_end_next"] > pairs["effective_end"])
        ]
        continued = cand["subscription_id"].isin(cont["subscription_id"])
        window_closed = (cand["effective_end"] + pd.to_timedelta(cand["grace_period_days"], unit="D")) < now
        churned = int((~continued & window_closed).sum())
        pending = int((~continued & ~window_closed).sum())
    members_at_start = len(at_start)
    churn_rate = safe_div(churned, members_at_start)
    return {
        "members_at_start": members_at_start,
        "churned_members": churned,
        "pending_members": pending,
        "churn_rate": rate(churn_rate if churn_rate is not None else 0.0),
    }


def month_summary(subs: pd.DataFrame, month_start: date, today: date, tz: str) -> dict[str, Any]:
    """Compact churn summary for one month (used by the Java admin dashboard)."""
    subs_p = prepare_subscriptions(subs, tz)
    m1 = month_end(month_start)
    churn = churn_stats(subs_p, month_start, m1, today)
    renewal = renewal_stats(subs_p, month_start, m1, today)
    return {
        "month": month_start.strftime("%Y-%m"),
        "churn_rate": churn["churn_rate"],
        "churned_members": churn["churned_members"],
        "members_at_start": churn["members_at_start"],
        "renewal_rate": renewal["renewal_rate"],
    }


def compute_memberships(
    subs: pd.DataFrame, date_from: date, date_to: date, today: date, tz: str
) -> dict[str, Any]:
    subs_p = prepare_subscriptions(subs, tz)
    last_day = min(date_to, today)
    months = month_starts(date_from, last_day) if date_from <= last_day else []

    monthly = []
    for m0 in months:
        m1 = month_end(m0)
        t0, t1 = pd.Timestamp(m0), pd.Timestamp(m1)
        renewal = renewal_stats(subs_p, m0, m1, today)
        churn = churn_stats(subs_p, m0, m1, today)
        frozen = subs_p[
            subs_p["frozen_from"].notna() & (subs_p["frozen_from"] <= t1) & (subs_p["frozen_until"] >= t0)
        ]
        overlap_days = (
            frozen["frozen_until"].clip(upper=t1) - frozen["frozen_from"].clip(lower=t0)
        ).dt.days + 1
        new_subs = subs_p[subs_p["committed"] & (subs_p["start"] >= t0) & (subs_p["start"] <= t1)]
        monthly.append(
            {
                "month": m0.strftime("%Y-%m"),
                **renewal,
                **churn,
                "new_subscriptions": len(new_subs),
                "subscriptions_frozen": len(frozen),
                "frozen_days": int(overlap_days.clip(lower=0).sum()),
            }
        )

    access = current_access(subs_p, today)
    plan_counts = access.groupby(["plan_id", "plan_name"]).size().sort_values(ascending=False)
    plan_mix = [
        {
            "plan_id": int(pid),
            "plan_name": name,
            "active_subscriptions": int(n),
            "share": rate(safe_div(n, len(access))),
        }
        for (pid, name), n in plan_counts.items()
    ]

    t = pd.Timestamp(today)
    upcoming = access[access["status"].isin({"ACTIVE", "FROZEN"}) & (access["end"] >= t)]
    upcoming = upcoming.assign(renewed=has_successor(subs_p, upcoming))
    expiries = []
    for days in EXPIRY_WINDOWS:
        window = upcoming[upcoming["end"] <= t + pd.Timedelta(days=days)]
        with_renewal = int(window["renewed"].sum())
        expiries.append(
            {
                "within_days": days,
                "expiring": len(window),
                "with_renewal": with_renewal,
                "without_renewal": int(len(window) - with_renewal),
            }
        )

    freeze = {
        "currently_frozen": int((access["status"] == "FROZEN").sum()),
        "active_subscriptions_with_freeze_used": int((access["freeze_count"] > 0).sum()),
        "share_with_freeze_used": rate(safe_div((access["freeze_count"] > 0).sum(), len(access))),
        "avg_freeze_days_used": num(access.loc[access["freeze_count"] > 0, "freeze_days_used"].mean()),
    }

    cancelled = subs_p[subs_p["status"].eq("CANCELLED") & subs_p["cancelled_date"].notna()]
    cancelled = cancelled[in_date_range(cancelled["cancelled_date"], date_from, date_to)]
    cancel_by_plan = (
        cancelled.assign(after_activation=cancelled["activated_at"].notna())
        .groupby(["plan_id", "plan_name"])
        .agg(cancellations=("subscription_id", "size"), after_activation=("after_activation", "sum"))
        .sort_values("cancellations", ascending=False)
    )
    cancellations = [
        {
            "plan_id": int(pid),
            "plan_name": name,
            "cancellations": int(r.cancellations),
            "after_activation": int(r.after_activation),
        }
        for (pid, name), r in cancel_by_plan.iterrows()
    ]

    totals_ended = sum(m["ended_subscriptions"] for m in monthly)
    totals_renewed = sum(m["renewed_subscriptions"] for m in monthly)
    summary = {
        "active_subscriptions": len(access),
        "ended_subscriptions": totals_ended,
        "renewed_subscriptions": totals_renewed,
        "renewal_rate": rate(safe_div(totals_renewed, totals_ended)),
        "avg_monthly_churn_rate": rate(pd.Series([m["churn_rate"] for m in monthly], dtype=float).mean())
        if monthly
        else None,
        "cancellations": len(cancelled),
    }
    return {
        "monthly": monthly,
        "plan_mix": plan_mix,
        "upcoming_expiries": expiries,
        "freeze_usage": freeze,
        "cancellations_by_plan": cancellations,
        "summary": summary,
        "as_of": today,
    }
