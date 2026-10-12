"""Application settings, loaded from environment variables (12-factor).

The service refuses to start when security-relevant settings are missing or weak
(e.g. a JWT secret shorter than 32 bytes), mirroring the Java core service.
"""

from __future__ import annotations

from functools import lru_cache
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

MIN_JWT_SECRET_BYTES = 32


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=None, case_sensitive=False, extra="ignore")

    app_name: str = "gymops-analytics"

    # --- database (read-only role) ---------------------------------------------------------
    database_url: str = Field(..., description="SQLAlchemy URL, e.g. postgresql+psycopg://user:pw@host/db")
    db_pool_size: int = Field(5, ge=1, le=50)
    db_max_overflow: int = Field(5, ge=0, le=50)
    db_pool_recycle_seconds: int = Field(1800, ge=60)
    db_connect_timeout_seconds: int = Field(5, ge=1, le=60)
    db_statement_timeout_ms: int = Field(15000, ge=1000, le=300000)

    # --- security (must match the Java core service) -------------------------------------------
    jwt_secret: str = Field(..., repr=False)
    jwt_issuer: str = "gymops-core"
    jwt_audience: str = "gymops-api"

    # --- behaviour ------------------------------------------------------------------------------
    cache_ttl_seconds: int = Field(300, ge=0, le=86400)
    cache_max_entries: int = Field(512, ge=1, le=100000)
    log_level: str = "INFO"
    business_timezone: str = "Asia/Kolkata"

    @field_validator("jwt_secret")
    @classmethod
    def _secret_strength(cls, value: str) -> str:
        if len(value.encode("utf-8")) < MIN_JWT_SECRET_BYTES:
            raise ValueError(f"JWT_SECRET must be at least {MIN_JWT_SECRET_BYTES} bytes")
        return value

    @field_validator("database_url")
    @classmethod
    def _normalise_db_url(cls, value: str) -> str:
        # Accept plain libpq-style URLs and force the psycopg 3 driver.
        for prefix in ("postgresql://", "postgres://"):
            if value.startswith(prefix):
                return "postgresql+psycopg://" + value[len(prefix) :]
        return value

    @field_validator("business_timezone")
    @classmethod
    def _valid_tz(cls, value: str) -> str:
        try:
            ZoneInfo(value)
        except (ZoneInfoNotFoundError, ValueError) as exc:
            raise ValueError(f"Unknown BUSINESS_TIMEZONE '{value}'") from exc
        return value

    @field_validator("log_level")
    @classmethod
    def _valid_level(cls, value: str) -> str:
        level = value.upper()
        if level not in {"DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"}:
            raise ValueError("LOG_LEVEL must be one of DEBUG, INFO, WARNING, ERROR, CRITICAL")
        return level

    @property
    def tz(self) -> ZoneInfo:
        return ZoneInfo(self.business_timezone)


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]
