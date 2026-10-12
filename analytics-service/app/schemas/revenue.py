from __future__ import annotations

from app.schemas.common import CamelModel, QueryMeta


class RevenuePeriod(CamelModel):
    period: str
    gross: float | None
    refunds: float | None
    net: float | None
    successful_payments: int
    failed_payments: int


class RevenueByPlan(CamelModel):
    plan_id: int | None
    plan_name: str | None
    gross: float | None
    refunds: float | None
    net: float | None
    successful_payments: int
    share_of_net: float | None


class RevenueByBranch(CamelModel):
    branch_id: int | None
    branch_name: str | None
    gross: float | None
    refunds: float | None
    net: float | None
    successful_payments: int
    share_of_net: float | None


class PaymentMethodShare(CamelModel):
    method: str
    payments: int
    amount: float | None
    share_of_amount: float | None
    share_of_payments: float | None


class RevenueSummary(CamelModel):
    gross: float | None
    refunds: float | None
    net: float | None
    successful_payments: int
    failed_payments: int
    payment_attempts: int
    failed_payment_rate: float | None
    refund_count: int
    paying_members: int
    avg_revenue_per_paying_member: float | None


class RevenueResponse(CamelModel):
    meta: QueryMeta
    summary: RevenueSummary
    series: list[RevenuePeriod]
    by_plan: list[RevenueByPlan]
    by_branch: list[RevenueByBranch]
    payment_methods: list[PaymentMethodShare]
