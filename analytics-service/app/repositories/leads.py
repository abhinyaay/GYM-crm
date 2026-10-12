"""Sales-pipeline extraction (leads, first activities). Leads are scoped by ``leads.branch_id``.

No lead PII is read (names, email, phone, notes, activity bodies stay in the database). Only
``users.full_name`` is read from ``users`` (the read-only role may only select
id, full_name, status, created_at on that table).
"""

from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime

import pandas as pd

from app.repositories.base import SqlRepository, branch_clause


class LeadRepository(SqlRepository):
    def fetch_leads(self, start: datetime, end: datetime, branch_ids: Sequence[int] | None) -> pd.DataFrame:
        """Leads created in [start, end) at branches in scope.

        Columns: lead_id, branch_id, source, status, owner_user_id, owner_name,
        trial_subscription_id, converted_at, lost_reason, created_at
        """
        params: dict = {"start": start, "end": end}
        scope = branch_clause("l.branch_id", branch_ids, params)
        return self._query(
            f"""
            SELECT l.id AS lead_id, l.branch_id, l.source, l.status, l.owner_user_id,
                   u.full_name AS owner_name, l.trial_subscription_id, l.converted_at,
                   l.lost_reason, l.created_at
              FROM leads l
              LEFT JOIN users u ON u.id = l.owner_user_id
             WHERE l.created_at >= :start AND l.created_at < :end
               AND {scope}
            """,
            params,
        )

    def fetch_first_activities(
        self, start: datetime, end: datetime, branch_ids: Sequence[int] | None
    ) -> pd.DataFrame:
        """Earliest activity per (lead, type) for leads created in [start, end) in scope.

        Columns: lead_id, type, created_at
        """
        params: dict = {"start": start, "end": end}
        scope = branch_clause("l.branch_id", branch_ids, params)
        return self._query(
            f"""
            SELECT la.lead_id, la.type, MIN(la.created_at) AS created_at
              FROM lead_activities la
              JOIN leads l ON l.id = la.lead_id
             WHERE l.created_at >= :start AND l.created_at < :end
               AND {scope}
             GROUP BY la.lead_id, la.type
            """,
            params,
        )
