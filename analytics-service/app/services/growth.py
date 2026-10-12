"""Referral programme and retention-campaign analytics (pure Pandas, no I/O).

Referrals are those *created* (business-tz date) in the range; statuses are current.

* conversionRate   (CONVERTED + REVERSED) / (referrals - REJECTED): share of eligible referrals
                   whose friend bought a first paid membership (a later reversal still counts as
                   a conversion; it is reported separately as reversalRate)
* reversalRate     REVERSED / (CONVERTED + REVERSED)
* medianDaysToConvert  median (converted_at - created_at) in days
* byChannel        LINK / DESK / SELF_SIGNUP: referrals, conversions, conversionRate
* rewards          creditIssued (STORE_CREDIT rewards AVAILABLE), creditPending (HELD),
                   creditReversed (REVERSED), codesIssued (DISCOUNT_CODE rewards AVAILABLE)

Campaign messages are those *sent* (business-tz date) in the range. A message converts when the
member paid an invoice within its attribution window (attributed by the core, last touch).

* campaigns        per campaign: messages, members, conversions, conversionRate, revenue,
                   offersIssued, offersRedeemed, redemptionRate (sorted by revenue, then messages)
* bySegment        the same, per audience segment
* totals           messages, conversions, conversionRate, revenue, revenuePerMessage

Rates are fractions in [0, 1] rounded to 4 decimals; ``None`` when the denominator is zero.
"""

from __future__ import annotations

from datetime import date
from typing import Any

import pandas as pd

from app.services.common import ensure_columns, in_date_range, money, num, rate, safe_div, to_local, to_utc

REFERRAL_COLUMNS = (
    "referral_id",
    "branch_id",
    "channel",
    "status",
    "rejection_reason",
    "referrer_reward_skipped",
    "created_at",
    "converted_at",
)
REWARD_COLUMNS = ("referral_id", "beneficiary", "reward_type", "amount", "status")
RECIPIENT_COLUMNS = (
    "campaign_id",
    "campaign_name",
    "segment",
    "member_id",
    "sent_at",
    "converted_at",
    "conversion_amount",
    "has_offer",
    "offer_redeemed",
)
REFERRAL_STATUSES = ("PENDING", "CONVERTED", "REVERSED", "REJECTED")
CHANNELS = ("LINK", "DESK", "SELF_SIGNUP")


def _in_range(df: pd.DataFrame, column: str, date_from: date, date_to: date, tz: str) -> pd.DataFrame:
    df[column] = to_utc(df[column])
    local = to_local(df[column], tz).dt.normalize()
    return df[in_date_range(local, date_from, date_to)].reset_index(drop=True)


def referral_summary(refs: pd.DataFrame) -> dict[str, Any]:
    counts = refs["status"].value_counts()
    by_status = {s: int(counts.get(s, 0)) for s in REFERRAL_STATUSES}
    ever_converted = by_status["CONVERTED"] + by_status["REVERSED"]
    eligible = len(refs) - by_status["REJECTED"]
    conv = refs[refs["converted_at"].notna()]
    days = (to_utc(conv["converted_at"]) - conv["created_at"]).dt.total_seconds().clip(lower=0) / 86400.0
    days = pd.to_numeric(days, errors="coerce").dropna()
    return {
        "total": len(refs),
        "by_status": by_status,
        "conversion_rate": rate(safe_div(ever_converted, eligible)),
        "reversal_rate": rate(safe_div(by_status["REVERSED"], ever_converted)),
        "capped": int((refs["referrer_reward_skipped"] == "MONTHLY_CAP").sum()),
        "median_days_to_convert": None if days.empty else num(days.median(), 1),
    }


def by_channel(refs: pd.DataFrame) -> list[dict[str, Any]]:
    out = []
    for channel in CHANNELS:
        part = refs[refs["channel"] == channel]
        eligible = part[part["status"] != "REJECTED"]
        conversions = int(eligible["status"].isin({"CONVERTED", "REVERSED"}).sum())
        out.append(
            {
                "channel": channel,
                "referrals": len(part),
                "conversions": conversions,
                "conversion_rate": rate(safe_div(conversions, len(eligible))),
            }
        )
    return out


def reward_summary(rewards: pd.DataFrame) -> dict[str, Any]:
    amount = pd.to_numeric(rewards["amount"], errors="coerce").fillna(0.0)
    credit = rewards["reward_type"] == "STORE_CREDIT"

    def credit_sum(status: str) -> float | None:
        return money(amount[credit & (rewards["status"] == status)].sum())

    return {
        "credit_issued": credit_sum("AVAILABLE"),
        "credit_pending": credit_sum("HELD"),
        "credit_reversed": credit_sum("REVERSED"),
        "codes_issued": int(
            ((rewards["reward_type"] == "DISCOUNT_CODE") & (rewards["status"] == "AVAILABLE")).sum()
        ),
        "rewards_reversed": int((rewards["status"] == "REVERSED").sum()),
    }


def _perf(group: pd.DataFrame) -> dict[str, Any]:
    messages = len(group)
    conversions = int(group["converted_at"].notna().sum())
    offers = int(group["has_offer"].fillna(False).astype(bool).sum())
    redeemed = int(group["offer_redeemed"].fillna(False).astype(bool).sum())
    revenue = pd.to_numeric(group["conversion_amount"], errors="coerce").fillna(0.0).sum()
    return {
        "messages": messages,
        "members": int(group["member_id"].nunique()),
        "conversions": conversions,
        "conversion_rate": rate(safe_div(conversions, messages)),
        "revenue": money(revenue),
        "offers_issued": offers,
        "offers_redeemed": redeemed,
        "redemption_rate": rate(safe_div(redeemed, offers)),
    }


def campaign_performance(rec: pd.DataFrame) -> list[dict[str, Any]]:
    rows = []
    for (campaign_id, name, segment), group in rec.groupby(
        ["campaign_id", "campaign_name", "segment"], sort=False
    ):
        rows.append({"campaign_id": int(campaign_id), "name": name, "segment": segment, **_perf(group)})
    return sorted(rows, key=lambda r: (-(r["revenue"] or 0.0), -r["messages"], r["campaign_id"]))


def segment_performance(rec: pd.DataFrame) -> list[dict[str, Any]]:
    rows = [{"segment": segment, **_perf(group)} for segment, group in rec.groupby("segment", sort=True)]
    return sorted(rows, key=lambda r: (-(r["revenue"] or 0.0), r["segment"]))


def campaign_totals(rec: pd.DataFrame) -> dict[str, Any]:
    perf = _perf(rec)
    return {
        "messages": perf["messages"],
        "conversions": perf["conversions"],
        "conversion_rate": perf["conversion_rate"],
        "revenue": perf["revenue"],
        "revenue_per_message": money(safe_div(perf["revenue"], perf["messages"])),
    }


def compute_growth(
    referrals: pd.DataFrame | None,
    rewards: pd.DataFrame | None,
    recipients: pd.DataFrame | None,
    date_from: date,
    date_to: date,
    tz: str,
) -> dict[str, Any]:
    refs = _in_range(ensure_columns(referrals, REFERRAL_COLUMNS), "created_at", date_from, date_to, tz)
    rw = ensure_columns(rewards, REWARD_COLUMNS)
    rw = rw[rw["referral_id"].isin(set(refs["referral_id"]))].reset_index(drop=True)
    rec = _in_range(ensure_columns(recipients, RECIPIENT_COLUMNS), "sent_at", date_from, date_to, tz)
    return {
        "referrals": referral_summary(refs),
        "by_channel": by_channel(refs),
        "rewards": reward_summary(rw),
        "campaigns": campaign_totals(rec),
        "campaign_performance": campaign_performance(rec),
        "by_segment": segment_performance(rec),
    }
