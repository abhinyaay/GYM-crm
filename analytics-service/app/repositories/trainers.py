"""Trainer extraction. Trainers are scoped by ``trainers.home_branch_id``.

Only ``users.full_name`` is read from ``users`` (the read-only role may only select
id, full_name, status, created_at on that table).
"""

from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime

import pandas as pd

from app.repositories.base import SqlRepository, branch_clause


class TrainerRepository(SqlRepository):
    def fetch_trainers(self, branch_ids: Sequence[int] | None) -> pd.DataFrame:
        """Columns: trainer_id, full_name, employee_code, home_branch_id, branch_name, status,
        max_active_clients"""
        params: dict = {}
        scope = branch_clause("t.home_branch_id", branch_ids, params)
        return self._query(
            f"""
            SELECT t.id AS trainer_id, u.full_name, t.employee_code, t.home_branch_id,
                   b.name AS branch_name, t.status, t.max_active_clients
              FROM trainers t
              JOIN users u ON u.id = t.user_id
              JOIN gym_branches b ON b.id = t.home_branch_id
             WHERE t.deleted_at IS NULL AND {scope}
            """,
            params,
        )

    def fetch_availability(self, branch_ids: Sequence[int] | None) -> pd.DataFrame:
        """Weekly slots. Columns: trainer_id, branch_id, day_of_week, start_time, end_time"""
        params: dict = {}
        scope = branch_clause("t.home_branch_id", branch_ids, params)
        return self._query(
            f"""
            SELECT ta.trainer_id, ta.branch_id, ta.day_of_week, ta.start_time, ta.end_time
              FROM trainer_availability ta
              JOIN trainers t ON t.id = ta.trainer_id
             WHERE t.deleted_at IS NULL AND {scope}
            """,
            params,
        )

    def fetch_sessions(
        self, start: datetime, end: datetime, branch_ids: Sequence[int] | None
    ) -> pd.DataFrame:
        """Sessions starting in [start, end). Columns: session_id, trainer_id, member_id, branch_id,
        starts_at, ends_at, status"""
        params: dict = {"start": start, "end": end}
        scope = branch_clause("t.home_branch_id", branch_ids, params)
        return self._query(
            f"""
            SELECT ts.id AS session_id, ts.trainer_id, ts.member_id, ts.branch_id,
                   ts.starts_at, ts.ends_at, ts.status
              FROM training_sessions ts
              JOIN trainers t ON t.id = ts.trainer_id
             WHERE ts.starts_at >= :start AND ts.starts_at < :end
               AND t.deleted_at IS NULL AND {scope}
            """,
            params,
        )

    def fetch_active_assignments(self, branch_ids: Sequence[int] | None) -> pd.DataFrame:
        """Current ACTIVE trainer-client assignments. Columns: trainer_id, member_id, assigned_at"""
        params: dict = {}
        scope = branch_clause("t.home_branch_id", branch_ids, params)
        return self._query(
            f"""
            SELECT mt.trainer_id, mt.member_id, mt.assigned_at
              FROM member_trainers mt
              JOIN trainers t ON t.id = mt.trainer_id
              JOIN members m ON m.id = mt.member_id
             WHERE mt.status = 'ACTIVE' AND t.deleted_at IS NULL AND m.deleted_at IS NULL AND {scope}
            """,
            params,
        )

    def fetch_client_visits(
        self, start: datetime, end: datetime, branch_ids: Sequence[int] | None
    ) -> pd.DataFrame:
        """Visits in [start, end) by members actively assigned to a trainer in scope.
        Columns: member_id, check_in_time"""
        params: dict = {"start": start, "end": end}
        scope = branch_clause("t.home_branch_id", branch_ids, params)
        return self._query(
            f"""
            SELECT a.member_id, a.check_in_time
              FROM attendance a
             WHERE a.check_in_time >= :start AND a.check_in_time < :end
               AND a.member_id IN (
                   SELECT mt.member_id
                     FROM member_trainers mt
                     JOIN trainers t ON t.id = mt.trainer_id
                    WHERE mt.status = 'ACTIVE' AND t.deleted_at IS NULL AND {scope})
            """,
            params,
        )
