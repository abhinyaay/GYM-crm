"""Referral-programme and retention-campaign extraction.

Referrals are scoped by ``referrals.branch_id`` (the referee's branch) and campaign recipients by
``campaign_recipients.branch_id`` (the member's home branch at send time). No personal data is read:
no names, emails, phones or offer codes, only ids, statuses, amounts and timestamps.
"""

from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime

import pandas as pd

from app.repositories.base import SqlRepository, branch_clause


class GrowthRepository(SqlRepository):
    def fetch_referrals(
        self, start: datetime, end: datetime, branch_ids: Sequence[int] | None
    ) -> pd.DataFrame:
        """Referrals created in [start, end) in scope.

        Columns: referral_id, branch_id, channel, status, rejection_reason, referrer_reward_skipped,
        created_at, converted_at
        """
        params: dict = {"start": start, "end": end}
        scope = branch_clause("r.branch_id", branch_ids, params)
        return self._query(
            f"""
            SELECT r.id AS referral_id, r.branch_id, r.channel, r.status, r.rejection_reason,
                   r.referrer_reward_skipped, r.created_at, r.converted_at
              FROM referrals r
             WHERE r.created_at >= :start AND r.created_at < :end
               AND {scope}
            """,
            params,
        )

    def fetch_rewards(self, start: datetime, end: datetime, branch_ids: Sequence[int] | None) -> pd.DataFrame:
        """Rewards of the referrals created in [start, end) in scope.

        Columns: referral_id, beneficiary, reward_type, amount, status
        """
        params: dict = {"start": start, "end": end}
        scope = branch_clause("r.branch_id", branch_ids, params)
        return self._query(
            f"""
            SELECT w.referral_id, w.beneficiary, w.reward_type, w.amount, w.status
              FROM referral_rewards w
              JOIN referrals r ON r.id = w.referral_id
             WHERE r.created_at >= :start AND r.created_at < :end
               AND {scope}
            """,
            params,
        )

    def fetch_recipients(
        self, start: datetime, end: datetime, branch_ids: Sequence[int] | None
    ) -> pd.DataFrame:
        """Campaign messages sent in [start, end) to members of branches in scope.

        Columns: campaign_id, campaign_name, segment, member_id, sent_at, converted_at,
        conversion_amount, has_offer, offer_redeemed
        """
        params: dict = {"start": start, "end": end}
        scope = branch_clause("cr.branch_id", branch_ids, params)
        return self._query(
            f"""
            SELECT cr.campaign_id, c.name AS campaign_name, c.segment, cr.member_id, cr.sent_at,
                   cr.converted_at, cr.conversion_amount, cr.discount_id IS NOT NULL AS has_offer,
                   cr.offer_redeemed
              FROM campaign_recipients cr
              JOIN campaigns c ON c.id = cr.campaign_id
             WHERE cr.sent_at >= :start AND cr.sent_at < :end
               AND {scope}
            """,
            params,
        )
