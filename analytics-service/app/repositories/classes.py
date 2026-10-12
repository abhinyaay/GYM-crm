"""Group-class extraction. Sessions are scoped by ``class_sessions.branch_id``.

Sessions are selected by ``starts_at`` inside the (margin-padded) UTC window; bookings are fetched
by joining back to the same session filter rather than passing a list of session ids. Exact
business-date filtering happens in the service layer.

Only ``users.full_name`` is read from ``users`` (the read-only role may only select
id, full_name, status, created_at on that table).
"""

from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime

import pandas as pd

from app.repositories.base import SqlRepository, branch_clause


class ClassesRepository(SqlRepository):
    def fetch_class_types(self) -> pd.DataFrame:
        """The class catalogue (small, global). Columns: class_type_id, name, category"""
        return self._query(
            """
            SELECT ct.id AS class_type_id, ct.name, ct.category
              FROM class_types ct
            """
        )

    def fetch_sessions(
        self, start: datetime, end: datetime, branch_ids: Sequence[int] | None
    ) -> pd.DataFrame:
        """Sessions starting in [start, end). Columns: session_id, class_type_id, branch_id,
        instructor_id, starts_at, ends_at, capacity, status"""
        params: dict = {"start": start, "end": end}
        scope = branch_clause("cs.branch_id", branch_ids, params)
        return self._query(
            f"""
            SELECT cs.id AS session_id, cs.class_type_id, cs.branch_id, cs.instructor_id,
                   cs.starts_at, cs.ends_at, cs.capacity, cs.status
              FROM class_sessions cs
             WHERE cs.starts_at >= :start AND cs.starts_at < :end AND {scope}
            """,
            params,
        )

    def fetch_bookings(
        self, start: datetime, end: datetime, branch_ids: Sequence[int] | None
    ) -> pd.DataFrame:
        """Bookings on sessions starting in [start, end). Columns: booking_id, session_id, status,
        promoted_at"""
        params: dict = {"start": start, "end": end}
        scope = branch_clause("cs.branch_id", branch_ids, params)
        return self._query(
            f"""
            SELECT cb.id AS booking_id, cb.session_id, cb.status, cb.promoted_at
              FROM class_bookings cb
              JOIN class_sessions cs ON cs.id = cb.session_id
             WHERE cs.starts_at >= :start AND cs.starts_at < :end AND {scope}
            """,
            params,
        )

    def fetch_instructors(
        self, start: datetime, end: datetime, branch_ids: Sequence[int] | None
    ) -> pd.DataFrame:
        """Instructors of sessions starting in [start, end) (including since-deleted trainers, so
        historical classes keep a name). Columns: trainer_id, full_name"""
        params: dict = {"start": start, "end": end}
        scope = branch_clause("cs.branch_id", branch_ids, params)
        return self._query(
            f"""
            SELECT t.id AS trainer_id, u.full_name
              FROM trainers t
              LEFT JOIN users u ON u.id = t.user_id
             WHERE t.id IN (
                   SELECT cs.instructor_id
                     FROM class_sessions cs
                    WHERE cs.starts_at >= :start AND cs.starts_at < :end AND {scope})
            """,
            params,
        )
