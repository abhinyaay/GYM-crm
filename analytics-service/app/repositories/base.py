"""Shared helpers for repositories.

Repositories only *extract* data: parameterised ``sqlalchemy.text`` queries returning DataFrames.
Date-range and branch filters are applied in SQL so only the relevant rows leave the database.
All transformation logic lives in ``app.services`` (pure Pandas, unit-testable without a DB).
"""

from __future__ import annotations

from collections.abc import Sequence
from datetime import UTC, date, datetime, time, timedelta
from typing import Any

import pandas as pd
from sqlalchemy import Engine, text

# Margin added on each side of a business-date range when filtering timestamptz columns in SQL.
# Exact local-date filtering (per branch timezone) happens in the service layer; the margin
# guarantees no row is lost for any timezone offset (max |offset| is 14h).
UTC_MARGIN = timedelta(days=2)


def utc_window(date_from: date, date_to: date) -> tuple[datetime, datetime]:
    """Index-friendly UTC bounds that safely enclose the inclusive local-date range."""
    start = datetime.combine(date_from, time.min, tzinfo=UTC) - UTC_MARGIN
    end = datetime.combine(date_to + timedelta(days=1), time.min, tzinfo=UTC) + UTC_MARGIN
    return start, end


def branch_clause(column: str, branch_ids: Sequence[int] | None, params: dict[str, Any]) -> str:
    """SQL predicate restricting ``column`` to the scope. ``column`` is always a code constant."""
    if branch_ids is None:
        return "TRUE"
    params["branch_ids"] = [int(b) for b in branch_ids]
    return f"{column} = ANY(CAST(:branch_ids AS bigint[]))"


class SqlRepository:
    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    def _query(self, sql: str, params: dict[str, Any] | None = None) -> pd.DataFrame:
        with self._engine.connect() as conn:
            return pd.read_sql(text(sql), conn, params=params or {})
