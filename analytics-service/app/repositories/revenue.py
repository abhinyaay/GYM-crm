"""Payment / refund extraction. Revenue is attributed to ``invoices.branch_id``; the plan of a
payment is resolved through invoices -> subscriptions -> membership_plans."""

from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime

import pandas as pd

from app.repositories.base import SqlRepository, branch_clause


class RevenueRepository(SqlRepository):
    def fetch_payments(
        self, start: datetime, end: datetime, branch_ids: Sequence[int] | None
    ) -> pd.DataFrame:
        """All payment attempts processed in [start, end).

        Columns: payment_id, member_id, amount, refunded_amount, method, status, processed_at,
        branch_id, branch_name, plan_id, plan_name
        """
        params: dict = {"start": start, "end": end}
        scope = branch_clause("i.branch_id", branch_ids, params)
        return self._query(
            f"""
            SELECT p.id AS payment_id, p.member_id, p.amount, p.refunded_amount, p.method, p.status,
                   p.processed_at, i.branch_id, b.name AS branch_name, s.plan_id, mp.name AS plan_name
              FROM payments p
              JOIN invoices i ON i.id = p.invoice_id
              JOIN gym_branches b ON b.id = i.branch_id
              LEFT JOIN subscriptions s ON s.id = i.subscription_id
              LEFT JOIN membership_plans mp ON mp.id = s.plan_id
             WHERE p.processed_at >= :start AND p.processed_at < :end AND {scope}
            """,
            params,
        )

    def fetch_refunds(self, start: datetime, end: datetime, branch_ids: Sequence[int] | None) -> pd.DataFrame:
        """Succeeded refunds processed in [start, end).

        Columns: refund_id, payment_id, amount, processed_at, branch_id, branch_name, plan_id, plan_name
        """
        params: dict = {"start": start, "end": end}
        scope = branch_clause("i.branch_id", branch_ids, params)
        return self._query(
            f"""
            SELECT r.id AS refund_id, r.payment_id, r.amount, r.processed_at,
                   i.branch_id, b.name AS branch_name, s.plan_id, mp.name AS plan_name
              FROM refunds r
              JOIN payments p ON p.id = r.payment_id
              JOIN invoices i ON i.id = p.invoice_id
              JOIN gym_branches b ON b.id = i.branch_id
              LEFT JOIN subscriptions s ON s.id = i.subscription_id
              LEFT JOIN membership_plans mp ON mp.id = s.plan_id
             WHERE r.status = 'SUCCEEDED'
               AND r.processed_at >= :start AND r.processed_at < :end AND {scope}
            """,
            params,
        )
