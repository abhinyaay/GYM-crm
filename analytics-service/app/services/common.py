"""Shared, pure helpers for the Pandas transformation layer (no I/O)."""

from __future__ import annotations

import math
from collections.abc import Iterable
from datetime import date, datetime, timedelta
from decimal import Decimal
from enum import StrEnum
from typing import Any

import numpy as np
import pandas as pd

WEEKDAYS = ("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")

# Subscription status groups (see core schema).
ACCESS_STATUSES = frozenset({"ACTIVE", "FROZEN", "GRACE"})
COMMITTED_STATUSES = frozenset({"SCHEDULED", "ACTIVE", "FROZEN", "GRACE", "EXPIRED", "SUPERSEDED"})


class Granularity(StrEnum):
    day = "day"
    week = "week"
    month = "month"


# --------------------------------------------------------------------------------------------
# rounding / JSON safety
# --------------------------------------------------------------------------------------------
def _finite(value: Any) -> float | None:
    if value is None or value is pd.NaT:
        return None
    try:
        f = float(value)
    except (TypeError, ValueError):
        return None
    return f if math.isfinite(f) else None


def money(value: Any) -> float | None:
    f = _finite(value)
    return None if f is None else round(f, 2) + 0.0


def rate(value: Any) -> float | None:
    f = _finite(value)
    return None if f is None else round(f, 4) + 0.0


def num(value: Any, digits: int = 2) -> float | None:
    f = _finite(value)
    return None if f is None else round(f, digits) + 0.0


def safe_div(numerator: Any, denominator: Any) -> float | None:
    n, d = _finite(numerator), _finite(denominator)
    if n is None or d is None or d == 0:
        return None
    return n / d


def json_safe(obj: Any) -> Any:
    """Recursively convert numpy/pandas scalars to JSON-safe Python values (NaN/inf -> None)."""
    if isinstance(obj, dict):
        return {k: json_safe(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple, set, frozenset)):
        return [json_safe(v) for v in obj]
    if obj is None or obj is pd.NaT:
        return None
    if isinstance(obj, (bool, np.bool_)):
        return bool(obj)
    if isinstance(obj, (int, np.integer)):
        return int(obj)
    if isinstance(obj, (float, np.floating, Decimal)):
        return _finite(obj)
    if isinstance(obj, pd.Timestamp):
        return obj.isoformat()
    if isinstance(obj, (datetime, date)):
        return obj.isoformat()
    if isinstance(obj, np.ndarray):
        return [json_safe(v) for v in obj.tolist()]
    try:
        if pd.isna(obj):
            return None
    except (TypeError, ValueError):
        pass
    return obj


# --------------------------------------------------------------------------------------------
# frame helpers
# --------------------------------------------------------------------------------------------
def ensure_columns(df: pd.DataFrame | None, columns: Iterable[str]) -> pd.DataFrame:
    """Return a copy of ``df`` guaranteed to contain ``columns`` (missing ones filled with NA)."""
    df = pd.DataFrame() if df is None else df.copy()
    for col in columns:
        if col not in df.columns:
            df[col] = pd.Series([None] * len(df), index=df.index, dtype="object")
    return df.reset_index(drop=True)


def to_utc(series: pd.Series) -> pd.Series:
    return pd.to_datetime(series, utc=True)


def to_local(ts_utc: pd.Series, tz: str | None = None, tz_series: pd.Series | None = None) -> pd.Series:
    """Convert tz-aware UTC timestamps to *naive local wall-clock* timestamps.

    Either a single IANA ``tz`` or a per-row ``tz_series`` (e.g. each branch's timezone).
    """
    ts_utc = to_utc(ts_utc)
    if len(ts_utc) == 0:
        return pd.Series(pd.to_datetime([]), index=ts_utc.index, dtype="datetime64[ns]")
    if tz_series is None:
        return ts_utc.dt.tz_convert(tz).dt.tz_localize(None).astype("datetime64[ns]")
    zones = tz_series.fillna(tz or "UTC")
    parts = [
        ts_utc[zones == zone].dt.tz_convert(zone).dt.tz_localize(None).astype("datetime64[ns]")
        for zone in zones.unique()
    ]
    return pd.concat(parts).reindex(ts_utc.index)


def to_dates(series: pd.Series) -> pd.Series:
    """Normalise a column of ``date`` objects / strings to naive midnight Timestamps."""
    return pd.to_datetime(series, errors="coerce").astype("datetime64[ns]").dt.normalize()


def to_float(series: pd.Series) -> pd.Series:
    return pd.to_numeric(
        series.map(lambda v: float(v) if isinstance(v, Decimal) else v), errors="coerce"
    ).astype(float)


def in_date_range(local_dates: pd.Series, date_from: date, date_to: date) -> pd.Series:
    return (local_dates >= pd.Timestamp(date_from)) & (local_dates <= pd.Timestamp(date_to))


# --------------------------------------------------------------------------------------------
# periods
# --------------------------------------------------------------------------------------------
def period_floor(local_dates: pd.Series, granularity: Granularity | str) -> pd.Series:
    """Map naive timestamps to the start of their period (week starts Monday)."""
    d = pd.to_datetime(local_dates).dt.normalize()
    g = Granularity(granularity)
    if g is Granularity.day:
        return d
    if g is Granularity.week:
        return d - pd.to_timedelta(d.dt.weekday, unit="D")
    return d - pd.to_timedelta(d.dt.day - 1, unit="D")


def period_floor_date(value: date, granularity: Granularity | str) -> date:
    g = Granularity(granularity)
    if g is Granularity.day:
        return value
    if g is Granularity.week:
        return value - timedelta(days=value.weekday())
    return value.replace(day=1)


def period_starts(date_from: date, date_to: date, granularity: Granularity | str) -> list[pd.Timestamp]:
    """All period starts intersecting [date_from, date_to]."""
    g = Granularity(granularity)
    current = period_floor_date(date_from, g)
    out: list[pd.Timestamp] = []
    while current <= date_to:
        out.append(pd.Timestamp(current))
        if g is Granularity.day:
            current += timedelta(days=1)
        elif g is Granularity.week:
            current += timedelta(days=7)
        else:
            current = (current.replace(day=28) + timedelta(days=4)).replace(day=1)
    return out


def month_starts(date_from: date, date_to: date) -> list[date]:
    return [p.date() for p in period_starts(date_from, date_to, Granularity.month)]


def month_end(month_start: date) -> date:
    return (month_start.replace(day=28) + timedelta(days=4)).replace(day=1) - timedelta(days=1)


def weekday_occurrences(date_from: date, date_to: date) -> list[int]:
    """Number of Mondays..Sundays (index 0..6) in the inclusive range."""
    counts = [0] * 7
    days = (date_to - date_from).days + 1
    if days <= 0:
        return counts
    full_weeks, remainder = divmod(days, 7)
    counts = [full_weeks] * 7
    for i in range(remainder):
        counts[(date_from.weekday() + i) % 7] += 1
    return counts
