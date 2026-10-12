"""Revenue analytics (pure Pandas).

Definitions (cash basis, business-timezone dates):

* **Gross revenue** = sum of ``payments.amount`` for successful payments, i.e. status SUCCEEDED,
  PARTIALLY_REFUNDED or REFUNDED (a refunded payment was still collected), bucketed by the local
  date of ``payments.processed_at``.
* **Refunds** = sum of ``refunds.amount`` with status SUCCEEDED, bucketed by ``refunds.processed_at``.
* **Net revenue** = gross - refunds.
* **Failed-payment rate** = FAILED attempts / all payment attempts processed in the range.
* **Average revenue per paying member** = net revenue / distinct members with >= 1 successful payment.
* Branch = ``invoices.branch_id``; plan = invoices -> subscriptions -> membership_plans (payments of
  invoices without a subscription are reported under plan ``null`` / "Unassigned").
"""

from __future__ import annotations

from datetime import date
from typing import Any

import pandas as pd

from app.services.common import (
    Granularity,
    ensure_columns,
    in_date_range,
    money,
    period_floor,
    period_starts,
    rate,
    safe_div,
    to_float,
    to_local,
)

SUCCESS_STATUSES = frozenset({"SUCCEEDED", "PARTIALLY_REFUNDED", "REFUNDED"})
PAYMENT_COLUMNS = (
    "payment_id",
    "member_id",
    "amount",
    "refunded_amount",
    "method",
    "status",
    "processed_at",
    "branch_id",
    "branch_name",
    "plan_id",
    "plan_name",
)
REFUND_COLUMNS = (
    "refund_id",
    "payment_id",
    "amount",
    "processed_at",
    "branch_id",
    "branch_name",
    "plan_id",
    "plan_name",
)
UNASSIGNED_PLAN = "Unassigned"


def _prepare(
    df: pd.DataFrame, columns: tuple[str, ...], date_from: date, date_to: date, tz: str
) -> pd.DataFrame:
    df = ensure_columns(df, columns)
    df["amount"] = to_float(df["amount"]).fillna(0.0)
    df["local_date"] = to_local(df["processed_at"], tz).dt.normalize()
    df = df[in_date_range(df["local_date"], date_from, date_to)].reset_index(drop=True)
    df["plan_key"] = pd.to_numeric(df["plan_id"], errors="coerce").fillna(-1).astype(int)
    df["plan_name"] = df["plan_name"].astype(object).where(df["plan_key"] >= 0, UNASSIGNED_PLAN)
    df["branch_key"] = pd.to_numeric(df["branch_id"], errors="coerce").fillna(-1).astype(int)
    return df


def prepare_payments(payments: pd.DataFrame, date_from: date, date_to: date, tz: str) -> pd.DataFrame:
    df = _prepare(payments, PAYMENT_COLUMNS, date_from, date_to, tz)
    df["success"] = df["status"].isin(SUCCESS_STATUSES)
    df["failed"] = df["status"].eq("FAILED")
    return df


def prepare_refunds(refunds: pd.DataFrame, date_from: date, date_to: date, tz: str) -> pd.DataFrame:
    return _prepare(refunds, REFUND_COLUMNS, date_from, date_to, tz)


def _breakdown(pay: pd.DataFrame, ref: pd.DataFrame, key: str, name_col: str) -> pd.DataFrame:
    ok = pay[pay["success"]]
    gross = ok.groupby([key, name_col]).agg(gross=("amount", "sum"), payments=("amount", "size"))
    refunds = ref.groupby([key, name_col]).agg(refunds=("amount", "sum"))
    out = gross.join(refunds, how="outer").fillna(0.0).reset_index()
    out["net"] = out["gross"] - out["refunds"]
    return out.sort_values(["net", key], ascending=[False, True])


def net_revenue_by_branch(
    payments: pd.DataFrame, refunds: pd.DataFrame, date_from: date, date_to: date, tz: str
) -> pd.DataFrame:
    """Columns: branch_key, gross, refunds, net (one row per branch with any activity)."""
    pay = prepare_payments(payments, date_from, date_to, tz)
    ref = prepare_refunds(refunds, date_from, date_to, tz)
    pay["branch_name"] = pay["branch_name"].astype(object).fillna("")
    ref["branch_name"] = ref["branch_name"].astype(object).fillna("")
    out = _breakdown(pay, ref, "branch_key", "branch_name")
    return out.groupby("branch_key")[["gross", "refunds", "net"]].sum()


def compute_revenue(
    payments: pd.DataFrame,
    refunds: pd.DataFrame,
    date_from: date,
    date_to: date,
    granularity: Granularity | str,
    tz: str,
) -> dict[str, Any]:
    pay = prepare_payments(payments, date_from, date_to, tz)
    ref = prepare_refunds(refunds, date_from, date_to, tz)
    periods = pd.DatetimeIndex(period_starts(date_from, date_to, granularity))
    pay["period"] = period_floor(pay["local_date"], granularity)
    ref["period"] = period_floor(ref["local_date"], granularity)
    ok = pay[pay["success"]]

    per = pd.DataFrame(index=periods)
    per["gross"] = ok.groupby("period")["amount"].sum()
    per["refunds"] = ref.groupby("period")["amount"].sum()
    per["payments"] = ok.groupby("period")["amount"].size()
    per["failed"] = pay[pay["failed"]].groupby("period")["amount"].size()
    per = per.fillna(0)
    series = [
        {
            "period": ts.date().isoformat(),
            "gross": money(r.gross),
            "refunds": money(r.refunds),
            "net": money(r.gross - r.refunds),
            "successful_payments": int(r.payments),
            "failed_payments": int(r.failed),
        }
        for ts, r in per.iterrows()
    ]

    gross_total = float(ok["amount"].sum())
    refund_total = float(ref["amount"].sum())
    net_total = gross_total - refund_total

    by_plan = [
        {
            "plan_id": None if int(r.plan_key) < 0 else int(r.plan_key),
            "plan_name": r.plan_name,
            "gross": money(r.gross),
            "refunds": money(r.refunds),
            "net": money(r.net),
            "successful_payments": int(r.payments),
            "share_of_net": rate(safe_div(r.net, net_total)),
        }
        for r in _breakdown(pay, ref, "plan_key", "plan_name").itertuples()
    ]

    pay["branch_name"] = pay["branch_name"].astype(object).fillna("")
    ref["branch_name"] = ref["branch_name"].astype(object).fillna("")
    by_branch = [
        {
            "branch_id": None if int(r.branch_key) < 0 else int(r.branch_key),
            "branch_name": r.branch_name or None,
            "gross": money(r.gross),
            "refunds": money(r.refunds),
            "net": money(r.net),
            "successful_payments": int(r.payments),
            "share_of_net": rate(safe_div(r.net, net_total)),
        }
        for r in _breakdown(pay, ref, "branch_key", "branch_name").itertuples()
    ]

    methods = ok.groupby("method").agg(payments=("amount", "size"), amount=("amount", "sum"))
    methods = methods.sort_values(["amount"], ascending=False)
    method_mix = [
        {
            "method": str(m),
            "payments": int(r.payments),
            "amount": money(r.amount),
            "share_of_amount": rate(safe_div(r.amount, gross_total)),
            "share_of_payments": rate(safe_div(r.payments, len(ok))),
        }
        for m, r in methods.iterrows()
    ]

    attempts = len(pay)
    failed = int(pay["failed"].sum())
    paying_members = int(ok["member_id"].nunique())
    summary = {
        "gross": money(gross_total),
        "refunds": money(refund_total),
        "net": money(net_total),
        "successful_payments": len(ok),
        "failed_payments": failed,
        "payment_attempts": attempts,
        "failed_payment_rate": rate(safe_div(failed, attempts)),
        "refund_count": len(ref),
        "paying_members": paying_members,
        "avg_revenue_per_paying_member": money(safe_div(net_total, paying_members)),
    }
    return {
        "series": series,
        "by_plan": by_plan,
        "by_branch": by_branch,
        "payment_methods": method_mix,
        "summary": summary,
    }
