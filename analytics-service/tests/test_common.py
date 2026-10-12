from datetime import date
from decimal import Decimal

import numpy as np
import pandas as pd

from app.services.common import (
    json_safe,
    money,
    month_end,
    period_floor,
    period_starts,
    rate,
    safe_div,
    to_local,
    weekday_occurrences,
)


def test_weekday_occurrences_two_full_weeks():
    assert weekday_occurrences(date(2026, 3, 2), date(2026, 3, 15)) == [2] * 7


def test_weekday_occurrences_partial_week():
    # Mon 2 .. Wed 4 March 2026
    assert weekday_occurrences(date(2026, 3, 2), date(2026, 3, 4)) == [1, 1, 1, 0, 0, 0, 0]


def test_period_starts_week_and_month():
    weeks = period_starts(date(2026, 3, 4), date(2026, 3, 20), "week")
    assert [w.date() for w in weeks] == [date(2026, 3, 2), date(2026, 3, 9), date(2026, 3, 16)]
    months = period_starts(date(2026, 1, 15), date(2026, 3, 1), "month")
    assert [m.date() for m in months] == [date(2026, 1, 1), date(2026, 2, 1), date(2026, 3, 1)]


def test_period_floor():
    s = pd.Series(pd.to_datetime(["2026-03-04 10:00", "2026-03-31 23:00"]))
    assert list(period_floor(s, "week")) == [pd.Timestamp("2026-03-02"), pd.Timestamp("2026-03-30")]
    assert list(period_floor(s, "month")) == [pd.Timestamp("2026-03-01")] * 2


def test_month_end_handles_leap_year():
    assert month_end(date(2028, 2, 1)) == date(2028, 2, 29)
    assert month_end(date(2026, 12, 1)) == date(2026, 12, 31)


def test_to_local_per_row_timezones():
    ts = pd.Series([pd.Timestamp("2026-03-01T20:00:00Z"), pd.Timestamp("2026-03-01T20:00:00Z")])
    zones = pd.Series(["Asia/Kolkata", "Asia/Dubai"])
    local = to_local(ts, tz_series=zones)
    assert list(local) == [pd.Timestamp("2026-03-02 01:30"), pd.Timestamp("2026-03-02 00:00")]


def test_json_safe_converts_numpy_nan_and_dates():
    payload = {
        "i": np.int64(3),
        "f": np.float64(1.5),
        "nan": float("nan"),
        "inf": np.inf,
        "d": date(2026, 1, 2),
        "ts": pd.Timestamp("2026-01-02T03:04:05Z"),
        "dec": Decimal("12.30"),
        "nested": [np.bool_(True), pd.NaT, None],
    }
    out = json_safe(payload)
    assert out == {
        "i": 3,
        "f": 1.5,
        "nan": None,
        "inf": None,
        "d": "2026-01-02",
        "ts": "2026-01-02T03:04:05+00:00",
        "dec": 12.3,
        "nested": [True, None, None],
    }
    assert type(out["i"]) is int and type(out["f"]) is float


def test_rounding_helpers():
    assert money(10.005) in (10.0, 10.01)  # binary float rounding, but always 2 dp
    assert money(1433.3333) == 1433.33
    assert rate(2 / 3) == 0.6667
    assert rate(float("nan")) is None
    assert safe_div(1, 0) is None
    assert money(-0.0001) == 0.0
