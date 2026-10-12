"""Base response models: snake_case in Python, camelCase on the wire."""

from __future__ import annotations

from datetime import date, datetime

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class CamelModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel, validate_by_name=True, validate_by_alias=True, serialize_by_alias=True
    )


class QueryMeta(CamelModel):
    branch_id: int | None = Field(None, description="Branch requested by the caller (null = all in scope)")
    scope_branch_ids: list[int] | None = Field(
        None, description="Branches the result covers; null = every branch (admin, no filter)"
    )
    date_from: date | None = Field(None, alias="from")
    date_to: date | None = Field(None, alias="to")
    granularity: str | None = None
    timezone: str = Field(..., description="Business timezone used for date bucketing")
    generated_at: datetime


class ErrorResponse(BaseModel):
    timestamp: str
    status: int
    error: str
    code: str
    message: str
    path: str
    correlationId: str | None = None  # noqa: N815 - wire format shared with the Java service
