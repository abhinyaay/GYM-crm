"""SQLAlchemy engine for the read-only analytics role.

* ``pool_pre_ping`` drops dead connections transparently after a database restart.
* Every session is pinned to UTC, read-only, and bounded by ``statement_timeout`` so a
  pathological analytics query can never hold resources on the OLTP database for long.
* ``application_name`` makes the service identifiable in ``pg_stat_activity``.
"""

from __future__ import annotations

import threading

from sqlalchemy import Engine, create_engine, text

from app.core.config import Settings, get_settings

_engine: Engine | None = None
_lock = threading.Lock()


def build_engine(settings: Settings) -> Engine:
    options = (
        f"-c statement_timeout={settings.db_statement_timeout_ms} "
        "-c TimeZone=UTC "
        "-c default_transaction_read_only=on"
    )
    return create_engine(
        settings.database_url,
        pool_pre_ping=True,
        pool_size=settings.db_pool_size,
        max_overflow=settings.db_max_overflow,
        pool_recycle=settings.db_pool_recycle_seconds,
        connect_args={
            "options": options,
            "application_name": settings.app_name,
            "connect_timeout": settings.db_connect_timeout_seconds,
        },
    )


def get_engine() -> Engine:
    """Lazily create the process-wide engine (no connection is opened until first use)."""
    global _engine
    if _engine is None:
        with _lock:
            if _engine is None:
                _engine = build_engine(get_settings())
    return _engine


def dispose_engine() -> None:
    global _engine
    with _lock:
        if _engine is not None:
            _engine.dispose()
            _engine = None


def check_database(engine: Engine) -> None:
    """Raise if the database is unreachable."""
    with engine.connect() as conn:
        conn.execute(text("SELECT 1"))
