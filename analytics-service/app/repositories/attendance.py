from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime

import pandas as pd

from app.repositories.base import SqlRepository, branch_clause


class AttendanceRepository(SqlRepository):
    def fetch_visits(self, start: datetime, end: datetime, branch_ids: Sequence[int] | None) -> pd.DataFrame:
        """Visits checked in within [start, end) at branches in scope, with the branch timezone.

        Columns: id, member_id, branch_id, check_in_time, check_out_time, auto_checked_out, timezone
        """
        params: dict = {"start": start, "end": end}
        scope = branch_clause("a.branch_id", branch_ids, params)
        return self._query(
            f"""
            SELECT a.id, a.member_id, a.branch_id, a.check_in_time, a.check_out_time,
                   a.auto_checked_out, b.timezone
              FROM attendance a
              JOIN gym_branches b ON b.id = a.branch_id
              JOIN members m ON m.id = a.member_id
             WHERE a.check_in_time >= :start AND a.check_in_time < :end
               AND b.deleted_at IS NULL
               AND m.deleted_at IS NULL
               AND {scope}
            """,
            params,
        )
