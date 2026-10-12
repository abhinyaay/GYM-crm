"""Attendance analytics (pure Pandas).

All visit timestamps are converted to the *branch's local time* (``gym_branches.timezone``)
before bucketing, so a 06:30 IST check-in is counted on the local day and hour it happened.
"""

from __future__ import annotations

from datetime import date
from typing import Any

import numpy as np
import pandas as pd

from app.services.common import (
    WEEKDAYS,
    Granularity,
    ensure_columns,
    in_date_range,
    num,
    period_floor,
    period_starts,
    safe_div,
    to_local,
    to_utc,
    weekday_occurrences,
)

VISIT_COLUMNS = (
    "id",
    "member_id",
    "branch_id",
    "check_in_time",
    "check_out_time",
    "auto_checked_out",
    "timezone",
)

# Used for occupancy when a visit has no trustworthy check-out (still open or auto-checked-out)
# and there are no genuine check-outs to derive a median from.
DEFAULT_VISIT_MINUTES = 60.0


def prepare_visits(
    visits: pd.DataFrame, date_from: date, date_to: date, default_tz: str = "UTC"
) -> pd.DataFrame:
    """Normalise raw visit rows and keep those whose *local* check-in date is in range.

    Adds: local_in, local_out, local_date, duration_minutes (NaN for open or auto-checked-out visits).
    """
    df = ensure_columns(visits, VISIT_COLUMNS)
    df["timezone"] = df["timezone"].fillna(default_tz).astype(object)
    df["check_in_time"] = to_utc(df["check_in_time"])
    df["check_out_time"] = to_utc(df["check_out_time"])
    df["local_in"] = to_local(df["check_in_time"], default_tz, tz_series=df["timezone"])
    has_out = df["check_out_time"].notna()
    local_out = pd.Series(pd.NaT, index=df.index, dtype="datetime64[ns]")
    if has_out.any():
        local_out.loc[has_out] = to_local(
            df.loc[has_out, "check_out_time"], default_tz, tz_series=df.loc[has_out, "timezone"]
        )
    df["local_out"] = local_out
    df["local_date"] = df["local_in"].dt.normalize()
    df = df[in_date_range(df["local_date"], date_from, date_to)].reset_index(drop=True)

    auto = df["auto_checked_out"].fillna(False).astype(bool)
    minutes = (df["check_out_time"] - df["check_in_time"]).dt.total_seconds() / 60.0
    df["duration_minutes"] = minutes.where(~auto & df["check_out_time"].notna())
    return df


def compute_attendance(
    visits: pd.DataFrame, date_from: date, date_to: date, granularity: Granularity | str = Granularity.day
) -> dict[str, Any]:
    """Visits / unique members / average duration per period, visits by weekday, and totals.

    * ``visits``: number of check-ins whose local date falls in the period.
    * ``uniqueMembers``: distinct members who checked in during the period.
    * ``avgDurationMinutes``: mean (check_out - check_in) over visits with a genuine check-out
      (auto-checked-out and still-open visits are excluded).
    """
    df = prepare_visits(visits, date_from, date_to)
    periods = period_starts(date_from, date_to, granularity)
    df["period"] = period_floor(df["local_date"], granularity)

    grouped = (
        df.groupby("period")
        .agg(
            visits=("member_id", "size"),
            unique_members=("member_id", "nunique"),
            avg_duration=("duration_minutes", "mean"),
        )
        .reindex(pd.DatetimeIndex(periods))
    )

    series = [
        {
            "period": ts.date().isoformat(),
            "visits": int(0 if pd.isna(row.visits) else row.visits),
            "unique_members": int(0 if pd.isna(row.unique_members) else row.unique_members),
            "avg_duration_minutes": num(row.avg_duration),
        }
        for ts, row in grouped.iterrows()
    ]

    occurrences = weekday_occurrences(date_from, date_to)
    by_wd = df["local_in"].dt.weekday.value_counts().reindex(range(7), fill_value=0)
    total = len(df)
    by_weekday = [
        {
            "weekday": WEEKDAYS[i],
            "iso_day": i + 1,
            "visits": int(by_wd.loc[i]),
            "avg_visits_per_day": num(safe_div(by_wd.loc[i], occurrences[i])),
            "share": num(safe_div(by_wd.loc[i], total), 4),
        }
        for i in range(7)
    ]

    unique_members = int(df["member_id"].nunique())
    summary = {
        "total_visits": total,
        "unique_members": unique_members,
        "avg_visits_per_member": num(safe_div(total, unique_members)),
        "avg_duration_minutes": num(df["duration_minutes"].mean()),
        "visits_with_duration": int(df["duration_minutes"].notna().sum()),
        "auto_checked_out_visits": int(df["auto_checked_out"].fillna(False).astype(bool).sum()),
        "avg_visits_per_day": num(safe_div(total, (date_to - date_from).days + 1)),
    }
    return {"series": series, "by_weekday": by_weekday, "summary": summary}


# --------------------------------------------------------------------------------------------
# peak hours & occupancy
# --------------------------------------------------------------------------------------------
def hourly_occupancy(prepared: pd.DataFrame, date_from: date, date_to: date) -> tuple[np.ndarray, float]:
    """Average concurrent occupancy for each local hour of day (0..23) over the date range.

    For every visit the interval [check-in, check-out) is split across the hourly slots it touches;
    the overlap (in hours) summed per slot equals the average head-count during that slot. Slots are
    then averaged over all days of the range (days without visits count as zero).

    Visits without a genuine check-out (open, or auto-checked-out by the system) are assumed to last
    the median genuine visit duration (or ``DEFAULT_VISIT_MINUTES``), never beyond any recorded
    check-out. Returns (24-element array, assumed visit minutes).
    """
    n_days = (date_to - date_from).days + 1
    median = prepared["duration_minutes"].median() if len(prepared) else np.nan
    assumed = float(median) if pd.notna(median) and median > 0 else DEFAULT_VISIT_MINUTES
    if prepared.empty or n_days <= 0:
        return np.zeros(24), assumed

    start = prepared["local_in"]
    assumed_end = start + pd.to_timedelta(assumed, unit="m")
    genuine = prepared["duration_minutes"].notna()
    recorded_out = prepared["local_out"]
    end = assumed_end.where(recorded_out.isna(), np.minimum(assumed_end, recorded_out))
    end = recorded_out.where(genuine, end)
    end = end.where(end > start, start + pd.Timedelta(minutes=1))

    slot0 = start.dt.floor("h")
    n_slots = np.ceil((end - slot0) / pd.Timedelta(hours=1)).astype(int).clip(lower=1)
    rep_idx = np.repeat(np.arange(len(prepared)), n_slots.to_numpy())
    offsets = pd.Series(rep_idx).groupby(rep_idx).cumcount().to_numpy()
    slot_start = slot0.to_numpy()[rep_idx] + offsets * np.timedelta64(1, "h")
    slot_end = slot_start + np.timedelta64(1, "h")
    v_start = start.to_numpy()[rep_idx]
    v_end = end.to_numpy()[rep_idx]
    overlap = (np.minimum(v_end, slot_end) - np.maximum(v_start, slot_start)) / np.timedelta64(1, "h")
    slots = pd.DataFrame({"slot_start": pd.to_datetime(slot_start), "overlap": np.clip(overlap, 0, None)})
    slots = slots[in_date_range(slots["slot_start"].dt.normalize(), date_from, date_to)]
    by_hour = slots.groupby(slots["slot_start"].dt.hour)["overlap"].sum().reindex(range(24), fill_value=0.0)
    return by_hour.to_numpy(dtype=float) / n_days, assumed


def compute_peak_hours(
    visits: pd.DataFrame, date_from: date, date_to: date, branch: dict[str, Any] | None = None, top_n: int = 3
) -> dict[str, Any]:
    """7x24 heatmap of average check-ins per occurrence of each weekday, top peak windows, and
    (when ``branch`` with ``capacity`` is given) average hourly occupancy vs capacity.

    ``avgCheckIns[d][h]`` = check-ins on local weekday d during local hour h / number of weekday-d
    dates in the range.
    """
    df = prepare_visits(visits, date_from, date_to)
    occurrences = weekday_occurrences(date_from, date_to)
    counts = (
        pd.crosstab(df["local_in"].dt.weekday.rename("weekday"), df["local_in"].dt.hour.rename("hour"))
        if len(df)
        else pd.DataFrame()
    )
    counts = counts.reindex(index=range(7), columns=range(24), fill_value=0)
    occ = pd.Series(occurrences, index=range(7)).replace(0, np.nan)
    avg = counts.div(occ, axis=0).fillna(0.0)

    heatmap = [
        {
            "weekday": WEEKDAYS[d],
            "iso_day": d + 1,
            "occurrences": occurrences[d],
            "total_check_ins": int(counts.loc[d].sum()),
            "avg_check_ins": [num(v) for v in avg.loc[d].tolist()],
        }
        for d in range(7)
    ]

    stacked = avg.stack().rename("value").reset_index()
    stacked.columns = ["weekday", "hour", "value"]
    stacked = stacked[stacked["value"] > 0].sort_values(
        ["value", "weekday", "hour"], ascending=[False, True, True]
    )
    peaks = [
        {
            "weekday": WEEKDAYS[int(r.weekday)],
            "iso_day": int(r.weekday) + 1,
            "hour": int(r.hour),
            "window": f"{int(r.hour):02d}:00-{(int(r.hour) + 1) % 24:02d}:00",
            "avg_check_ins": num(r.value),
        }
        for r in stacked.head(top_n).itertuples()
    ]

    occupancy = None
    if branch is not None:
        capacity = branch.get("capacity")
        hours, assumed = hourly_occupancy(df, date_from, date_to)
        rows = [
            {"hour": h, "avg_occupancy": num(v), "utilisation_pct": num(safe_div(v * 100, capacity))}
            for h, v in enumerate(hours)
        ]
        peak_hour = int(np.argmax(hours)) if hours.max() > 0 else None
        occupancy = {
            "branch_id": branch.get("branch_id"),
            "branch_name": branch.get("name"),
            "capacity": capacity,
            "assumed_visit_minutes": num(assumed),
            "hours": rows,
            "peak_hour": peak_hour,
            "peak_avg_occupancy": num(hours.max()) if peak_hour is not None else 0.0,
            "peak_utilisation_pct": num(safe_div(hours.max() * 100, capacity))
            if peak_hour is not None
            else 0.0,
        }

    return {
        "total_check_ins": len(df),
        "heatmap": heatmap,
        "peak_windows": peaks,
        "occupancy": occupancy,
    }
