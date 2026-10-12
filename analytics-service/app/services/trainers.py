"""Trainer analytics (pure Pandas).

Per trainer, for sessions whose local (business tz) start date is in the range:

* sessions by status (SCHEDULED / COMPLETED / CANCELLED / NO_SHOW)
* completionRate = COMPLETED / (COMPLETED + CANCELLED + NO_SHOW)   (resolved sessions only)
* noShowRate     = NO_SHOW   / (COMPLETED + CANCELLED + NO_SHOW)
* bookedHours    = duration of COMPLETED + SCHEDULED sessions
* availableHours = weekly availability slots expanded over every date in the range
                   (slot hours x number of matching ISO weekdays in the range)
* utilisationPct = bookedHours / availableHours x 100
* activeClients  = current ACTIVE member_trainers assignments
* avgClientVisits = check-ins in the range by current active clients / activeClients
"""

from __future__ import annotations

from datetime import date
from typing import Any

import pandas as pd

from app.services.common import (
    ensure_columns,
    in_date_range,
    num,
    rate,
    safe_div,
    to_local,
    weekday_occurrences,
)

TRAINER_COLUMNS = (
    "trainer_id",
    "full_name",
    "employee_code",
    "home_branch_id",
    "branch_name",
    "status",
    "max_active_clients",
)
AVAILABILITY_COLUMNS = ("trainer_id", "branch_id", "day_of_week", "start_time", "end_time")
SESSION_COLUMNS = ("session_id", "trainer_id", "member_id", "branch_id", "starts_at", "ends_at", "status")
ASSIGNMENT_COLUMNS = ("trainer_id", "member_id", "assigned_at")
SESSION_STATUSES = ("SCHEDULED", "COMPLETED", "CANCELLED", "NO_SHOW")


def _time_to_hours(series: pd.Series) -> pd.Series:
    return pd.to_timedelta(series.astype(str), errors="coerce").dt.total_seconds() / 3600.0


def available_hours(availability: pd.DataFrame, date_from: date, date_to: date) -> pd.Series:
    """Available hours per trainer over the inclusive date range."""
    av = ensure_columns(availability, AVAILABILITY_COLUMNS)
    if av.empty:
        return pd.Series(dtype=float)
    occ = weekday_occurrences(date_from, date_to)
    hours = (_time_to_hours(av["end_time"]) - _time_to_hours(av["start_time"])).clip(lower=0)
    days = pd.to_numeric(av["day_of_week"], errors="coerce").fillna(0).astype(int)
    occurrences = days.map(lambda d: occ[d - 1] if 1 <= d <= 7 else 0)
    return (hours * occurrences).groupby(av["trainer_id"]).sum()


def compute_trainers(
    trainers: pd.DataFrame,
    availability: pd.DataFrame,
    sessions: pd.DataFrame,
    assignments: pd.DataFrame,
    client_visits: pd.DataFrame,
    date_from: date,
    date_to: date,
    tz: str,
) -> dict[str, Any]:
    tr = ensure_columns(trainers, TRAINER_COLUMNS)

    ss = ensure_columns(sessions, SESSION_COLUMNS)
    ss["starts_at"] = pd.to_datetime(ss["starts_at"], utc=True)
    ss["ends_at"] = pd.to_datetime(ss["ends_at"], utc=True)
    ss = ss[in_date_range(to_local(ss["starts_at"], tz).dt.normalize(), date_from, date_to)].copy()
    ss["hours"] = (ss["ends_at"] - ss["starts_at"]).dt.total_seconds() / 3600.0
    counts = pd.crosstab(ss["trainer_id"], ss["status"]) if len(ss) else pd.DataFrame()
    counts = counts.reindex(columns=list(SESSION_STATUSES), fill_value=0)
    booked = ss[ss["status"].isin({"COMPLETED", "SCHEDULED"})].groupby("trainer_id")["hours"].sum()

    avail = available_hours(availability, date_from, date_to)

    asg = ensure_columns(assignments, ASSIGNMENT_COLUMNS).drop_duplicates(["trainer_id", "member_id"])
    clients = asg.groupby("trainer_id")["member_id"].nunique()

    cv = ensure_columns(client_visits, ("member_id", "check_in_time"))
    cv = cv[in_date_range(to_local(cv["check_in_time"], tz).dt.normalize(), date_from, date_to)]
    visits_per_member = cv.groupby("member_id").size()
    asg["visits"] = asg["member_id"].map(visits_per_member).fillna(0)
    client_visits_total = asg.groupby("trainer_id")["visits"].sum()

    items = []
    for t in tr.sort_values(["full_name", "trainer_id"]).itertuples(index=False):
        tid = t.trainer_id
        c = counts.loc[tid] if tid in counts.index else pd.Series(0, index=list(SESSION_STATUSES))
        scheduled, completed, cancelled, no_show = (int(c[s]) for s in SESSION_STATUSES)
        resolved = completed + cancelled + no_show
        booked_h = float(booked.get(tid, 0.0))
        avail_h = float(avail.get(tid, 0.0))
        n_clients = int(clients.get(tid, 0))
        items.append(
            {
                "trainer_id": int(tid),
                "name": t.full_name,
                "employee_code": t.employee_code,
                "branch_id": None if pd.isna(t.home_branch_id) else int(t.home_branch_id),
                "branch_name": t.branch_name,
                "status": t.status,
                "active_clients": n_clients,
                "max_active_clients": None if pd.isna(t.max_active_clients) else int(t.max_active_clients),
                "sessions_scheduled": scheduled,
                "sessions_completed": completed,
                "sessions_cancelled": cancelled,
                "sessions_no_show": no_show,
                "completion_rate": rate(safe_div(completed, resolved)),
                "no_show_rate": rate(safe_div(no_show, resolved)),
                "booked_hours": num(booked_h),
                "available_hours": num(avail_h),
                "utilisation_pct": num(safe_div(booked_h * 100, avail_h)),
                "avg_client_visits": num(safe_div(client_visits_total.get(tid, 0.0), n_clients)),
            }
        )

    total_booked = sum(i["booked_hours"] or 0 for i in items)
    total_avail = sum(i["available_hours"] or 0 for i in items)
    summary = {
        "trainers": len(items),
        "active_clients": sum(i["active_clients"] for i in items),
        "sessions_completed": sum(i["sessions_completed"] for i in items),
        "sessions_no_show": sum(i["sessions_no_show"] for i in items),
        "booked_hours": num(total_booked),
        "available_hours": num(total_avail),
        "utilisation_pct": num(safe_div(total_booked * 100, total_avail)),
    }
    return {"trainers": items, "summary": summary}
