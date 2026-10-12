"""Group-class analytics (pure Pandas).

Sessions are bucketed by the business-local (``tz``) date/time of ``starts_at``; only sessions whose
local start date is in [date_from, date_to] count. CANCELLED sessions are counted in
``cancelled_sessions`` only; every other metric (capacity, bookings, waitlist) covers the
non-cancelled sessions.

* occupied        = bookings with status BOOKED, ATTENDED or NO_SHOW
* fillRate        = sum(occupied) / sum(capacity)
* attendanceRate  = ATTENDED / (ATTENDED + NO_SHOW)
* lateCancelRate  = LATE_CANCELLED / bookings that are not WAITLISTED
* waitlistJoins   = bookings that are WAITLISTED or were promoted (promoted_at set)
* waitlistPromotions = bookings with promoted_at set
* sessionsFull    = sessions with occupied >= capacity
* time slots      = ISO weekday (1=Mon..7=Sun) x local hour of the session start
* instructor avgFillRate = mean of the per-session fill rates of the sessions they taught
* daily           = every date in the range, zero-filled
"""

from __future__ import annotations

from datetime import date
from typing import Any

import pandas as pd

from app.services.common import ensure_columns, in_date_range, num, rate, safe_div, to_local

CLASS_TYPE_COLUMNS = ("class_type_id", "name", "category")
SESSION_COLUMNS = (
    "session_id",
    "class_type_id",
    "branch_id",
    "instructor_id",
    "starts_at",
    "ends_at",
    "capacity",
    "status",
)
BOOKING_COLUMNS = ("booking_id", "session_id", "status", "promoted_at")
INSTRUCTOR_COLUMNS = ("trainer_id", "full_name")

OCCUPYING_STATUSES = frozenset({"BOOKED", "ATTENDED", "NO_SHOW"})
CANCELLED = "CANCELLED"

# per-session booking counters, summed by every grouping
COUNTERS = (
    "occupied",
    "attended",
    "no_shows",
    "late_cancels",
    "late_cancel_base",
    "waitlist_joins",
    "promotions",
)


def _booking_counters(bookings: pd.DataFrame) -> pd.DataFrame:
    """Per-session booking counts indexed by session_id (columns: COUNTERS)."""
    bk = ensure_columns(bookings, BOOKING_COLUMNS)
    status = bk["status"].astype("object")
    promoted = bk["promoted_at"].notna()
    flags = pd.DataFrame(
        {
            "session_id": bk["session_id"],
            "occupied": status.isin(OCCUPYING_STATUSES),
            "attended": status.eq("ATTENDED"),
            "no_shows": status.eq("NO_SHOW"),
            "late_cancels": status.eq("LATE_CANCELLED"),
            "late_cancel_base": ~status.eq("WAITLISTED"),
            "waitlist_joins": status.eq("WAITLISTED") | promoted,
            "promotions": promoted,
        }
    )
    return flags.groupby("session_id")[list(COUNTERS)].sum()


def _prepare_sessions(
    sessions: pd.DataFrame, bookings: pd.DataFrame, date_from: date, date_to: date, tz: str
) -> pd.DataFrame:
    ss = ensure_columns(sessions, SESSION_COLUMNS)
    ss["starts_at"] = pd.to_datetime(ss["starts_at"], utc=True)
    local = to_local(ss["starts_at"], tz)
    ss["local_date"] = local.dt.normalize()
    ss = ss[in_date_range(ss["local_date"], date_from, date_to)].copy()
    local = local.loc[ss.index]
    ss["weekday"] = (local.dt.weekday + 1).astype(int)
    ss["hour"] = local.dt.hour.astype(int)
    ss["capacity"] = pd.to_numeric(ss["capacity"], errors="coerce").fillna(0).astype(int)

    counters = _booking_counters(bookings)
    for col in COUNTERS:
        ss[col] = ss["session_id"].map(counters[col]).fillna(0).astype(int)
    ss["fill_rate"] = (ss["occupied"] / ss["capacity"].where(ss["capacity"] > 0)).astype(float)
    return ss.reset_index(drop=True)


def _rates(occupied: int, capacity: int, attended: int, no_shows: int) -> dict[str, float | None]:
    return {
        "fill_rate": rate(safe_div(occupied, capacity)),
        "attendance_rate": rate(safe_div(attended, attended + no_shows)),
    }


def _totals(df: pd.DataFrame) -> dict[str, int]:
    out = {col: int(df[col].sum()) for col in (*COUNTERS, "capacity")}
    out["sessions"] = len(df)
    return out


def _summary(active: pd.DataFrame, cancelled_sessions: int) -> dict[str, Any]:
    t = _totals(active)
    return {
        "sessions": t["sessions"],
        "cancelled_sessions": cancelled_sessions,
        "capacity": t["capacity"],
        "occupied": t["occupied"],
        "fill_rate": rate(safe_div(t["occupied"], t["capacity"])),
        "attended": t["attended"],
        "no_shows": t["no_shows"],
        "attendance_rate": rate(safe_div(t["attended"], t["attended"] + t["no_shows"])),
        "late_cancels": t["late_cancels"],
        "late_cancel_rate": rate(safe_div(t["late_cancels"], t["late_cancel_base"])),
        "waitlist_joins": t["waitlist_joins"],
        "waitlist_promotions": t["promotions"],
        "sessions_full": int((active["occupied"] >= active["capacity"]).sum()),
        "avg_occupied_per_session": num(safe_div(t["occupied"], t["sessions"])),
    }


def _by_class_type(active: pd.DataFrame, class_types: pd.DataFrame) -> list[dict[str, Any]]:
    ct = ensure_columns(class_types, CLASS_TYPE_COLUMNS).drop_duplicates("class_type_id")
    lookup = ct.set_index("class_type_id")
    items = []
    for type_id, grp in active.groupby("class_type_id", sort=True):
        t = _totals(grp)
        known = type_id in lookup.index
        items.append(
            {
                "class_type_id": int(type_id),
                "name": lookup.at[type_id, "name"] if known else None,
                "category": lookup.at[type_id, "category"] if known else None,
                "sessions": t["sessions"],
                "capacity": t["capacity"],
                "occupied": t["occupied"],
                **_rates(t["occupied"], t["capacity"], t["attended"], t["no_shows"]),
                "attended": t["attended"],
                "no_shows": t["no_shows"],
                "late_cancels": t["late_cancels"],
                "late_cancel_rate": rate(safe_div(t["late_cancels"], t["late_cancel_base"])),
                "waitlist_joins": t["waitlist_joins"],
            }
        )
    items.sort(key=lambda i: (-i["occupied"], i["class_type_id"]))
    return items


def _time_slots(active: pd.DataFrame) -> list[dict[str, Any]]:
    items = []
    for (weekday, hour), grp in active.groupby(["weekday", "hour"], sort=True):
        t = _totals(grp)
        items.append(
            {
                "weekday": int(weekday),
                "hour": int(hour),
                "sessions": t["sessions"],
                "occupied": t["occupied"],
                "capacity": t["capacity"],
                "fill_rate": rate(safe_div(t["occupied"], t["capacity"])),
                "waitlist_joins": t["waitlist_joins"],
            }
        )
    return items


def _instructors(active: pd.DataFrame, instructors: pd.DataFrame) -> list[dict[str, Any]]:
    ins = ensure_columns(instructors, INSTRUCTOR_COLUMNS).drop_duplicates("trainer_id")
    names = ins.set_index("trainer_id")["full_name"]
    items = []
    for trainer_id, grp in active[active["instructor_id"].notna()].groupby("instructor_id", sort=True):
        t = _totals(grp)
        mean_fill = grp["fill_rate"].mean()  # NaN (capacity 0) sessions are skipped
        items.append(
            {
                "trainer_id": int(trainer_id),
                "name": names.get(trainer_id),
                "sessions_taught": t["sessions"],
                "occupied": t["occupied"],
                "avg_fill_rate": rate(mean_fill),
                "attended": t["attended"],
                "no_shows": t["no_shows"],
                "attendance_rate": rate(safe_div(t["attended"], t["attended"] + t["no_shows"])),
            }
        )
    items.sort(key=lambda i: (-i["sessions_taught"], i["trainer_id"]))
    return items


def _daily(active: pd.DataFrame, date_from: date, date_to: date) -> list[dict[str, Any]]:
    days = pd.date_range(pd.Timestamp(date_from), pd.Timestamp(date_to), freq="D")
    cols = ["occupied", "capacity", "attended"]
    grouped = active.groupby("local_date")[cols].sum() if len(active) else pd.DataFrame(columns=cols)
    counts = active.groupby("local_date").size() if len(active) else pd.Series(dtype=int)
    grouped = grouped.reindex(days, fill_value=0)
    counts = counts.reindex(days, fill_value=0)
    return [
        {
            "date": day.date(),
            "sessions": int(counts.loc[day]),
            "occupied": int(row.occupied),
            "capacity": int(row.capacity),
            "attended": int(row.attended),
            "fill_rate": rate(safe_div(row.occupied, row.capacity)),
        }
        for day, row in grouped.iterrows()
    ]


def compute_classes(
    class_types: pd.DataFrame,
    sessions: pd.DataFrame,
    bookings: pd.DataFrame,
    instructors: pd.DataFrame,
    date_from: date,
    date_to: date,
    tz: str,
) -> dict[str, Any]:
    ss = _prepare_sessions(sessions, bookings, date_from, date_to, tz)
    is_cancelled = ss["status"].astype("object").eq(CANCELLED)
    active = ss[~is_cancelled]
    return {
        "summary": _summary(active, int(is_cancelled.sum())),
        "by_class_type": _by_class_type(active, class_types),
        "time_slots": _time_slots(active),
        "instructors": _instructors(active, instructors),
        "daily": _daily(active, date_from, date_to),
    }
