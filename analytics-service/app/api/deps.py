"""FastAPI dependencies: repositories (overridable in tests), request context, range validation."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, timedelta
from typing import Annotated, Any

from fastapi import Query

from app.core.cache import get_cache
from app.core.config import Settings
from app.core.errors import ValidationFailedError
from app.core.security import BranchScope, Principal, resolve_scope
from app.db.session import check_database, get_engine
from app.repositories.attendance import AttendanceRepository
from app.repositories.branches import BranchRepository
from app.repositories.members import MemberRepository
from app.repositories.revenue import RevenueRepository
from app.repositories.trainers import TrainerRepository
from app.services.common import Granularity, json_safe

DEFAULT_RANGE_DAYS = 90
MAX_RANGE_DAYS = 730

ERROR_RESPONSES: dict[int | str, dict[str, Any]] = {
    400: {"description": "VALIDATION_FAILED"},
    401: {"description": "UNAUTHORIZED - missing, invalid or expired access token"},
    403: {"description": "ACCESS_DENIED - role not allowed or branch outside the caller's scope"},
    503: {"description": "ANALYTICS_UNAVAILABLE - analytics database unreachable"},
}


# --- repositories (one instance per request; the engine/pool is process-wide) -------------------
def get_attendance_repository() -> AttendanceRepository:
    return AttendanceRepository(get_engine())


def get_branch_repository() -> BranchRepository:
    return BranchRepository(get_engine())


def get_member_repository() -> MemberRepository:
    return MemberRepository(get_engine())


def get_revenue_repository() -> RevenueRepository:
    return RevenueRepository(get_engine())


def get_trainer_repository() -> TrainerRepository:
    return TrainerRepository(get_engine())


def get_db_check() -> Callable[[], None]:
    return lambda: check_database(get_engine())


# --- request context --------------------------------------------------------------------------
def business_today(settings: Settings) -> date:
    return datetime.now(settings.tz).date()


def resolve_range(date_from: date | None, date_to: date | None, today: date) -> tuple[date, date]:
    """Default: the last 90 days ending today. ``from`` must not be after ``to``; max span 730 days."""
    if date_to is None:
        date_to = today
    if date_from is None:
        date_from = date_to - timedelta(days=DEFAULT_RANGE_DAYS - 1)
    if date_from > date_to:
        raise ValidationFailedError("'from' must be on or before 'to'")
    if (date_to - date_from).days > MAX_RANGE_DAYS:
        raise ValidationFailedError(f"Date range must not exceed {MAX_RANGE_DAYS} days")
    return date_from, date_to


@dataclass
class AnalyticsContext:
    scope: BranchScope
    today: date
    tz: str
    date_from: date | None = None
    date_to: date | None = None
    granularity: Granularity | None = None
    extra: dict[str, Any] = field(default_factory=dict)

    def cache_key(self) -> tuple:
        return (
            self.scope.cache_key(),
            self.date_from,
            self.date_to,
            self.granularity.value if self.granularity else None,
            self.today,
            self.tz,
            tuple(sorted(self.extra.items())),
        )

    def meta(self, generated_at: datetime) -> dict[str, Any]:
        return {
            "branch_id": self.scope.requested_branch_id,
            "scope_branch_ids": None if self.scope.branch_ids is None else list(self.scope.branch_ids),
            "date_from": self.date_from,
            "date_to": self.date_to,
            "granularity": self.granularity.value if self.granularity else None,
            "timezone": self.tz,
            "generated_at": generated_at,
        }


def build_context(
    principal: Principal,
    settings: Settings,
    branch_id: int | None,
    date_from: date | None = None,
    date_to: date | None = None,
    granularity: Granularity | None = None,
    use_range: bool = True,
    **extra: Any,
) -> AnalyticsContext:
    scope = resolve_scope(principal, branch_id)  # 403 before any validation of the range
    today = business_today(settings)
    ctx = AnalyticsContext(
        scope=scope, today=today, tz=settings.business_timezone, granularity=granularity, extra=extra
    )
    if use_range:
        ctx.date_from, ctx.date_to = resolve_range(date_from, date_to, today)
    return ctx


def now_utc() -> datetime:
    return datetime.now(UTC)


def cached_response(
    endpoint: str, ctx: AnalyticsContext, compute: Callable[[], dict[str, Any]]
) -> dict[str, Any]:
    """Compute (or reuse) the JSON-safe payload for ``ctx`` and attach request metadata."""

    def run() -> dict[str, Any]:
        return {"generated_at": now_utc(), "data": json_safe(compute())}

    entry = get_cache().get_or_compute(endpoint, ctx.cache_key(), run)
    return {"meta": ctx.meta(entry["generated_at"]), **entry["data"]}


# --- shared query parameters --------------------------------------------------------------------
BranchIdParam = Annotated[
    int | None,
    Query(
        alias="branchId",
        ge=1,
        description="Restrict to one branch. Managers may only pass branches they are assigned to; "
        "when omitted, managers see their assigned branches and admins see all branches.",
    ),
]
FromParam = Annotated[
    date | None, Query(alias="from", description="Inclusive start date (default: to - 89 days)")
]
ToParam = Annotated[
    date | None, Query(alias="to", description="Inclusive end date (default: today, business tz)")
]
GranularityParam = Annotated[Granularity, Query(description="Bucket size for time series")]
