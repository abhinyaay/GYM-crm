from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends

from app.api.deps import (
    ERROR_RESPONSES,
    BranchIdParam,
    FromParam,
    ToParam,
    build_context,
    cached_response,
    get_trainer_repository,
)
from app.core.config import Settings, get_settings
from app.core.security import Principal, require_analytics_access
from app.repositories.base import utc_window
from app.repositories.trainers import TrainerRepository
from app.schemas.trainers import TrainersResponse
from app.services.trainers import compute_trainers

router = APIRouter(prefix="/analytics", tags=["trainers"])


@router.get(
    "/trainers",
    response_model=TrainersResponse,
    responses=ERROR_RESPONSES,
    summary="Per-trainer sessions, completion/no-show rates and utilisation",
)
def trainers(
    branch_id: BranchIdParam = None,
    date_from: FromParam = None,
    date_to: ToParam = None,
    principal: Principal = Depends(require_analytics_access),
    settings: Settings = Depends(get_settings),
    repo: TrainerRepository = Depends(get_trainer_repository),
) -> dict[str, Any]:
    """Trainers are attributed to their home branch. completionRate = COMPLETED / (COMPLETED +
    CANCELLED + NO_SHOW); noShowRate = NO_SHOW / same denominator; bookedHours = COMPLETED + SCHEDULED
    session hours; availableHours = weekly availability expanded over the range; utilisationPct =
    bookedHours / availableHours x 100; avgClientVisits = visits by current active clients / clients."""
    ctx = build_context(principal, settings, branch_id, date_from, date_to)

    def compute() -> dict[str, Any]:
        b = ctx.scope.branch_ids
        window = utc_window(ctx.date_from, ctx.date_to)
        return compute_trainers(
            repo.fetch_trainers(b),
            repo.fetch_availability(b),
            repo.fetch_sessions(*window, b),
            repo.fetch_active_assignments(b),
            repo.fetch_client_visits(*window, b),
            ctx.date_from,
            ctx.date_to,
            ctx.tz,
        )

    return cached_response("trainers", ctx, compute)
