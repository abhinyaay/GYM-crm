"""Small in-process TTL cache for computed analytics responses.

Analytics tolerate a few minutes of staleness, so each computed response is cached per worker
process, keyed on ``(endpoint, normalised query params, resolved branch scope)``. The branch scope
is part of the key, so callers with different access never share an entry. TTL comes from
``CACHE_TTL_SECONDS`` (default 300 s; 0 disables caching). Each uvicorn worker has its own cache.
"""

from __future__ import annotations

import threading
from collections.abc import Callable, Hashable
from typing import Any

from cachetools import TTLCache
from prometheus_client import Counter

from app.core.config import get_settings

CACHE_EVENTS = Counter(
    "gymops_analytics_cache_events_total", "Analytics response cache lookups", ["endpoint", "result"]
)


class ResponseCache:
    def __init__(self, ttl_seconds: int, max_entries: int) -> None:
        self.ttl_seconds = ttl_seconds
        self._cache: TTLCache | None = (
            TTLCache(maxsize=max_entries, ttl=ttl_seconds) if ttl_seconds > 0 else None
        )
        self._lock = threading.Lock()

    def get_or_compute(self, endpoint: str, key: Hashable, compute: Callable[[], Any]) -> Any:
        if self._cache is None:
            return compute()
        full_key = (endpoint, key)
        with self._lock:
            if full_key in self._cache:
                CACHE_EVENTS.labels(endpoint=endpoint, result="hit").inc()
                return self._cache[full_key]
        CACHE_EVENTS.labels(endpoint=endpoint, result="miss").inc()
        value = compute()  # computed outside the lock; a concurrent miss just computes twice
        with self._lock:
            self._cache[full_key] = value
        return value

    def clear(self) -> None:
        with self._lock:
            if self._cache is not None:
                self._cache.clear()


_cache: ResponseCache | None = None
_cache_lock = threading.Lock()


def get_cache() -> ResponseCache:
    global _cache
    if _cache is None:
        with _cache_lock:
            if _cache is None:
                s = get_settings()
                _cache = ResponseCache(s.cache_ttl_seconds, s.cache_max_entries)
    return _cache


def reset_cache() -> None:
    global _cache
    with _cache_lock:
        _cache = None
