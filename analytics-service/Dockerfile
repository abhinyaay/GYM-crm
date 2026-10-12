# syntax=docker/dockerfile:1

# ---- build stage: resolve dependencies into an isolated virtualenv ---------------------------
FROM python:3.12-slim AS builder

ENV PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /build
COPY requirements.txt .
# All dependencies ship binary wheels (pandas, numpy, psycopg[binary]); no compiler is needed.
RUN python -m venv /opt/venv \
 && /opt/venv/bin/pip install --upgrade pip \
 && /opt/venv/bin/pip install --only-binary=:all: -r requirements.txt

# ---- runtime stage: slim image, non-root, no build tooling -----------------------------------
FROM python:3.12-slim AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PATH="/opt/venv/bin:$PATH" \
    PROMETHEUS_MULTIPROC_DIR=/tmp/prometheus

RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app \
 && mkdir -p /tmp/prometheus \
 && chown app:app /tmp/prometheus

WORKDIR /app
COPY --from=builder /opt/venv /opt/venv
COPY --chown=root:root app ./app

USER app
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD ["python", "-c", "import sys, urllib.request; r = urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3); sys.exit(0 if r.status == 200 else 1)"]

# Validate configuration once (fail fast), then hand over to the CMD.
ENTRYPOINT ["/bin/sh", "-c", "python -m app.preflight && exec \"$@\"", "--"]
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "2", "--proxy-headers"]
