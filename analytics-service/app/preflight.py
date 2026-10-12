"""Container preflight: validate configuration *before* uvicorn forks its workers.

With ``--workers N`` the uvicorn supervisor keeps restarting workers that crash on import, so an
invalid configuration (e.g. a JWT_SECRET shorter than 32 bytes) would never stop the container.
The Docker ENTRYPOINT runs this module first and only execs uvicorn when the settings are valid.
"""

from __future__ import annotations

import sys

from pydantic import ValidationError

from app.core.config import get_settings


def main() -> int:
    try:
        get_settings()
    except ValidationError as exc:
        print(f"gymops-analytics: invalid configuration - {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
