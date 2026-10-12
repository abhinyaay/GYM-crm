"""Sales-pipeline analytics: leads & free trials (pure Pandas, no I/O).

Leads are the ones whose ``created_at`` local (business tz) date is in the range. ``status`` is
the lead's *current* status, so the funnel is a snapshot of where that cohort stands today.

* funnel             count per status in pipeline order (all six always present)
* conversionRate     CONVERTED / leads
* bySource           per source: leads, conversions, conversionRate (sorted by leads desc)
* trialToPaidRate    CONVERTED / leads that had a trial (trial_subscription_id not null)
* medianDaysToConvert median of (converted_at - created_at) in days over converted leads
* weeklyTrend        per ISO week (Monday start): new leads (by created_at) and conversions of
                     those leads (by converted_at, when it falls inside the range); empty weeks = 0
* lostReasons        count per lost_reason for LOST leads (sorted by count desc)
* ownerLeaderboard   per owner: leadsHandled, conversions, conversionRate (unassigned leads skipped;
                     their count is reported as summary.unassignedLeads)
* followUpSla        a lead is "contacted" at its first CALL / EMAIL / WHATSAPP / SMS / VISIT
                     activity; rate = leads contacted within 24h of creation / all leads in range

Rates are fractions in [0, 1] rounded to 4 decimals; ``None`` when the denominator is zero.
"""

from __future__ import annotations

from datetime import date
from typing import Any

import pandas as pd

from app.services.common import (
    Granularity,
    ensure_columns,
    in_date_range,
    num,
    period_floor,
    period_starts,
    rate,
    safe_div,
    to_local,
    to_utc,
)

LEAD_STATUSES = ("NEW", "CONTACTED", "TOUR_SCHEDULED", "TRIAL_ACTIVE", "CONVERTED", "LOST")
CONVERTED = "CONVERTED"
LOST = "LOST"
CONTACT_TYPES = frozenset({"CALL", "EMAIL", "WHATSAPP", "SMS", "VISIT"})
SLA_HOURS = 24.0

LEAD_COLUMNS = (
    "lead_id",
    "branch_id",
    "source",
    "status",
    "owner_user_id",
    "owner_name",
    "trial_subscription_id",
    "converted_at",
    "lost_reason",
    "created_at",
)
ACTIVITY_COLUMNS = ("lead_id", "type", "created_at")


def _median(series: pd.Series, digits: int = 1) -> float | None:
    s = pd.to_numeric(series, errors="coerce").dropna()
    return None if s.empty else num(s.median(), digits)


def prepare_leads(leads: pd.DataFrame | None, date_from: date, date_to: date, tz: str) -> pd.DataFrame:
    """Normalise types and keep leads whose local creation date is within [date_from, date_to]."""
    df = ensure_columns(leads, LEAD_COLUMNS)
    df["created_at"] = to_utc(df["created_at"])
    df["converted_at"] = to_utc(df["converted_at"])
    df["created_local"] = to_local(df["created_at"], tz)
    df = df[in_date_range(df["created_local"].dt.normalize(), date_from, date_to)].copy()
    df["is_converted"] = df["status"] == CONVERTED
    return df.reset_index(drop=True)


def compute_funnel(df: pd.DataFrame) -> dict[str, Any]:
    total = len(df)
    counts = df["status"].value_counts()
    stages = [
        {"status": s, "count": int(counts.get(s, 0)), "share": rate(safe_div(counts.get(s, 0), total))}
        for s in LEAD_STATUSES
    ]
    return {"total_leads": total, "stages": stages}


def median_days_to_convert(df: pd.DataFrame) -> float | None:
    conv = df[df["is_converted"] & df["converted_at"].notna()]
    days = (conv["converted_at"] - conv["created_at"]).dt.total_seconds().clip(lower=0) / 86400.0
    return _median(days)


def compute_summary(df: pd.DataFrame) -> dict[str, Any]:
    total = len(df)
    conversions = int(df["is_converted"].sum())
    trials = df[df["trial_subscription_id"].notna()]
    trial_conversions = int(trials["is_converted"].sum())
    return {
        "total_leads": total,
        "open_leads": int((~df["status"].isin({CONVERTED, LOST})).sum()),
        "conversions": conversions,
        "conversion_rate": rate(safe_div(conversions, total)),
        "lost_leads": int((df["status"] == LOST).sum()),
        "trial_leads": len(trials),
        "trial_conversions": trial_conversions,
        "trial_to_paid_rate": rate(safe_div(trial_conversions, len(trials))),
        "median_days_to_convert": median_days_to_convert(df),
        "unassigned_leads": int(df["owner_user_id"].isna().sum()),
    }


def conversion_by_source(df: pd.DataFrame) -> list[dict[str, Any]]:
    if df.empty:
        return []
    g = (
        df.assign(source=df["source"].fillna("OTHER"))
        .groupby("source")
        .agg(leads=("lead_id", "size"), conversions=("is_converted", "sum"))
        .reset_index()
        .sort_values(["leads", "conversions", "source"], ascending=[False, False, True])
    )
    return [
        {
            "source": r.source,
            "leads": int(r.leads),
            "conversions": int(r.conversions),
            "conversion_rate": rate(safe_div(r.conversions, r.leads)),
        }
        for r in g.itertuples(index=False)
    ]


def weekly_trend(df: pd.DataFrame, date_from: date, date_to: date, tz: str) -> list[dict[str, Any]]:
    weeks = period_starts(date_from, date_to, Granularity.week)
    created = period_floor(df["created_local"], Granularity.week).value_counts()

    conv = df[df["is_converted"] & df["converted_at"].notna()]
    conv_local = to_local(conv["converted_at"], tz).dt.normalize()
    conv_local = conv_local[in_date_range(conv_local, date_from, date_to)]
    converted = period_floor(conv_local, Granularity.week).value_counts()

    return [
        {
            "week_start": w.date().isoformat(),
            "leads": int(created.get(w, 0)),
            "conversions": int(converted.get(w, 0)),
        }
        for w in weeks
    ]


def lost_reasons(df: pd.DataFrame) -> list[dict[str, Any]]:
    lost = df[df["status"] == LOST]
    if lost.empty:
        return []
    counts = lost["lost_reason"].fillna("OTHER").value_counts()
    rows = sorted(counts.items(), key=lambda kv: (-int(kv[1]), str(kv[0])))
    return [
        {"reason": str(reason), "count": int(n), "share": rate(safe_div(n, len(lost)))} for reason, n in rows
    ]


def owner_leaderboard(df: pd.DataFrame) -> list[dict[str, Any]]:
    owned = df[df["owner_user_id"].notna()]
    if owned.empty:
        return []
    owned = owned.assign(owner_user_id=pd.to_numeric(owned["owner_user_id"]).astype("int64"))
    g = (
        owned.groupby("owner_user_id")
        .agg(
            owner_name=("owner_name", "first"),
            leads=("lead_id", "size"),
            conversions=("is_converted", "sum"),
        )
        .reset_index()
    )
    g["sort_name"] = g["owner_name"].fillna("").astype(str)
    g = g.sort_values(
        ["conversions", "leads", "sort_name", "owner_user_id"], ascending=[False, False, True, True]
    )
    return [
        {
            "owner_user_id": int(r.owner_user_id),
            "owner_name": None if pd.isna(r.owner_name) else str(r.owner_name),
            "leads_handled": int(r.leads),
            "conversions": int(r.conversions),
            "conversion_rate": rate(safe_div(r.conversions, r.leads)),
        }
        for r in g.itertuples(index=False)
    ]


def follow_up_sla(df: pd.DataFrame, activities: pd.DataFrame | None) -> dict[str, Any]:
    total = len(df)
    act = ensure_columns(activities, ACTIVITY_COLUMNS)
    act = act[act["type"].isin(CONTACT_TYPES) & act["lead_id"].isin(set(df["lead_id"]))]
    first_contact = to_utc(act["created_at"]).groupby(act["lead_id"]).min().to_dict()

    contacted = df[df["lead_id"].isin(set(first_contact))]
    first_at = to_utc(contacted["lead_id"].map(first_contact).astype(object))
    delta = (first_at - contacted["created_at"]).dt.total_seconds()
    hours = delta.clip(lower=0) / 3600.0
    within = int((hours <= SLA_HOURS).sum())
    return {
        "sla_hours": int(SLA_HOURS),
        "total_leads": total,
        "contacted_leads": len(contacted),
        "contacted_within_24h": within,
        "rate": rate(safe_div(within, total)),
        "median_hours_to_first_contact": _median(hours),
    }


def compute_leads(
    leads: pd.DataFrame | None,
    activities: pd.DataFrame | None,
    date_from: date,
    date_to: date,
    tz: str,
) -> dict[str, Any]:
    df = prepare_leads(leads, date_from, date_to, tz)
    return {
        "summary": compute_summary(df),
        "funnel": compute_funnel(df),
        "by_source": conversion_by_source(df),
        "weekly_trend": weekly_trend(df, date_from, date_to, tz),
        "lost_reasons": lost_reasons(df),
        "owner_leaderboard": owner_leaderboard(df),
        "follow_up_sla": follow_up_sla(df, activities),
    }
