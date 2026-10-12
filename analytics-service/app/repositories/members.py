"""Member / subscription extraction. Member-centric metrics are scoped by ``members.home_branch_id``."""

from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime

import pandas as pd

from app.repositories.base import SqlRepository, branch_clause
from app.repositories.branches import OPEN_COMPLAINT_STATUSES


class MemberRepository(SqlRepository):
    def fetch_members(self, branch_ids: Sequence[int] | None) -> pd.DataFrame:
        """Non-deleted members. Columns: member_id, member_code, first_name, last_name,
        home_branch_id, branch_name, status, registered_at"""
        params: dict = {}
        scope = branch_clause("m.home_branch_id", branch_ids, params)
        return self._query(
            f"""
            SELECT m.id AS member_id, m.member_code, m.first_name, m.last_name, m.home_branch_id,
                   b.name AS branch_name, m.status, m.registered_at
              FROM members m
              JOIN gym_branches b ON b.id = m.home_branch_id
             WHERE m.deleted_at IS NULL AND {scope}
            """,
            params,
        )

    def fetch_subscriptions(self, branch_ids: Sequence[int] | None) -> pd.DataFrame:
        """All subscriptions of non-deleted members in scope, with plan attributes.

        Columns: subscription_id, member_id, home_branch_id, branch_id, plan_id, plan_code, plan_name,
        grace_period_days, previous_subscription_id, status, change_type, start_date, end_date,
        activated_at, cancelled_at, frozen_from, frozen_until, freeze_days_used, freeze_count
        """
        params: dict = {}
        scope = branch_clause("m.home_branch_id", branch_ids, params)
        return self._query(
            f"""
            SELECT s.id AS subscription_id, s.member_id, m.home_branch_id, s.branch_id,
                   s.plan_id, p.code AS plan_code, p.name AS plan_name, p.grace_period_days,
                   s.previous_subscription_id, s.status, s.change_type, s.start_date, s.end_date,
                   s.activated_at, s.cancelled_at, s.frozen_from, s.frozen_until,
                   s.freeze_days_used, s.freeze_count
              FROM subscriptions s
              JOIN members m ON m.id = s.member_id
              JOIN membership_plans p ON p.id = s.plan_id
             WHERE m.deleted_at IS NULL AND {scope}
            """,
            params,
        )

    def fetch_member_visits(self, since: datetime, branch_ids: Sequence[int] | None) -> pd.DataFrame:
        """Visits (at any branch) since ``since`` by members whose home branch is in scope.
        Columns: member_id, check_in_time"""
        params: dict = {"since": since}
        scope = branch_clause("m.home_branch_id", branch_ids, params)
        return self._query(
            f"""
            SELECT a.member_id, a.check_in_time
              FROM attendance a
              JOIN members m ON m.id = a.member_id
             WHERE a.check_in_time >= :since AND m.deleted_at IS NULL AND {scope}
            """,
            params,
        )

    def fetch_last_visits(self, branch_ids: Sequence[int] | None) -> pd.DataFrame:
        """Most recent check-in per member. Columns: member_id, last_check_in"""
        params: dict = {}
        scope = branch_clause("m.home_branch_id", branch_ids, params)
        return self._query(
            f"""
            SELECT a.member_id, max(a.check_in_time) AS last_check_in
              FROM attendance a
              JOIN members m ON m.id = a.member_id
             WHERE m.deleted_at IS NULL AND {scope}
             GROUP BY a.member_id
            """,
            params,
        )

    def fetch_failed_payments(self, since: datetime, branch_ids: Sequence[int] | None) -> pd.DataFrame:
        """Failed payment attempts since ``since``. Columns: payment_id, member_id, processed_at"""
        params: dict = {"since": since}
        scope = branch_clause("m.home_branch_id", branch_ids, params)
        return self._query(
            f"""
            SELECT p.id AS payment_id, p.member_id, p.processed_at
              FROM payments p
              JOIN members m ON m.id = p.member_id
             WHERE p.status = 'FAILED' AND p.processed_at >= :since
               AND m.deleted_at IS NULL AND {scope}
            """,
            params,
        )

    def fetch_open_member_complaints(self, since: datetime, branch_ids: Sequence[int] | None) -> pd.DataFrame:
        """Open complaints raised for members since ``since``.
        Columns: complaint_id, member_id, status, created_at"""
        params: dict = {"since": since, "statuses": list(OPEN_COMPLAINT_STATUSES)}
        scope = branch_clause("m.home_branch_id", branch_ids, params)
        return self._query(
            f"""
            SELECT c.id AS complaint_id, c.member_id, c.status, c.created_at
              FROM complaints c
              JOIN members m ON m.id = c.member_id
             WHERE c.created_at >= :since
               AND c.status = ANY(CAST(:statuses AS varchar[]))
               AND m.deleted_at IS NULL AND {scope}
            """,
            params,
        )
