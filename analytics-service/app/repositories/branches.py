from __future__ import annotations

from collections.abc import Sequence

import pandas as pd

from app.repositories.base import SqlRepository, branch_clause

OPEN_COMPLAINT_STATUSES = ("OPEN", "IN_PROGRESS", "ESCALATED", "REOPENED")


class BranchRepository(SqlRepository):
    def fetch_branches(self, branch_ids: Sequence[int] | None) -> pd.DataFrame:
        """Columns: branch_id, code, name, capacity, timezone, status"""
        params: dict = {}
        scope = branch_clause("b.id", branch_ids, params)
        return self._query(
            f"""
            SELECT b.id AS branch_id, b.code, b.name, b.capacity, b.timezone, b.status
              FROM gym_branches b
             WHERE b.deleted_at IS NULL AND {scope}
             ORDER BY b.id
            """,
            params,
        )

    def fetch_open_complaints(self, branch_ids: Sequence[int] | None) -> pd.DataFrame:
        """Currently open complaints. Columns: complaint_id, branch_id, member_id, status, created_at"""
        params: dict = {"statuses": list(OPEN_COMPLAINT_STATUSES)}
        scope = branch_clause("c.branch_id", branch_ids, params)
        return self._query(
            f"""
            SELECT c.id AS complaint_id, c.branch_id, c.member_id, c.status, c.created_at
              FROM complaints c
             WHERE c.status = ANY(CAST(:statuses AS varchar[])) AND {scope}
            """,
            params,
        )
