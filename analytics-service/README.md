# GymOps Analytics Service

Read-only analytics for GymOps, built with Python 3.12, FastAPI and Pandas.

## Responsibility boundary

| Java core service (`core-service/`) | This service (`analytics-service/`) |
|---|---|
| Owns all transactional CRUD (members, plans, subscriptions, payments, attendance, ...) and every write to PostgreSQL | Never writes. Connects as the read-only role `gym_analytics_ro` (see `V3__analytics_read_access.sql`). Sessions are also forced to `default_transaction_read_only=on`. |
| Issues JWTs | Verifies the same JWTs (shared HS256 secret) |
| Owns the schema (Flyway) | Reads the tables with `SELECT` only. From `users` it reads only `full_name` (the role may read only id, full_name, status, created_at). |

Layers:

- `app/repositories/` does extraction only. Each query is a parameterised `sqlalchemy.text` query that returns a DataFrame. Date-range and branch filters run in SQL, with a ±2-day UTC margin so that no time zone loses rows.
- `app/services/` holds pure Pandas transforms (DataFrames in, plain dicts out, no I/O). Every transform is unit-tested with small hand-built frames.
- `app/api/routers/` handles HTTP, authentication, branch scope, validation and caching.
- `app/schemas/` holds the Pydantic response models. Fields are snake_case in Python and camelCase in JSON.

## Endpoints

All endpoints are `GET` and return JSON. They are documented at `/docs` and `/openapi.json`. The service listens on port 8000. Nginx forwards `/analytics/*` unchanged; `/docs`, `/health` and `/metrics` are served at the root of the service.

Common parameters:

- `branchId` (int, optional).
- `from` and `to` (ISO dates). The default range is the last 90 days ending today in the business time zone. `from` must not be after `to`, and the range may span at most 730 days.
- `granularity` (`day` | `week` | `month`, default `day`). Weeks start on Monday.

| Endpoint | Parameters | Content |
|---|---|---|
| `/analytics/attendance` | branchId, from, to, granularity | Visits, unique members and average visit duration per period; visits by weekday; summary |
| `/analytics/attendance/peak-hours` | branchId, from, to | 7×24 heatmap; top 3 peak windows; hourly occupancy against capacity (only when exactly one branch is in scope) |
| `/analytics/revenue` | branchId, from, to, granularity | Gross, refunds and net per period; by plan; by branch; payment-method mix; failed-payment rate; average revenue per paying member |
| `/analytics/members` | branchId, from, to, granularity, cohortMonths (1–24, default 12) | New registrations per period; active members; engagement segments; retention cohorts |
| `/analytics/members/churn-risk` | branchId, minBand (LOW\|MEDIUM\|HIGH), page (0-based), size (1–200, default 50) | Rule-based risk scores with reasons, plus the rule catalogue |
| `/analytics/memberships` | branchId, from, to | Monthly renewal rate, churn rate, new subscriptions and freezes; plan mix; upcoming expiries; freeze usage; cancellations by plan |
| `/analytics/memberships/churn` | branchId, month (`YYYY-MM`, default current month; future months rejected) | `{"branchId", "month", "churnRate", "churnedMembers", "membersAtStart", "renewalRate"}` |
| `/analytics/trainers` | branchId, from, to | Per trainer: clients, sessions by status, completion and no-show rates, booked and available hours, utilisation, average client visits |
| `/analytics/branches` | branchId, from, to | Per branch comparison ranked by net revenue |
| `/health` | none | Liveness check; does not touch the database |
| `/health/ready` | none | Runs `SELECT 1`; returns 200 `UP` or 503 `DOWN` |
| `/metrics` | none | Prometheus metrics. Not authenticated, so keep it internal (do not expose it through Nginx). |

Every analytics response except `/memberships/churn` includes a `meta` object with these fields:

- `branchId`
- `scopeBranchIds` (`null` means all branches)
- `from`, `to`
- `granularity`
- `timezone`
- `generatedAt`

Number formats:

- Money is rounded to 2 decimal places.
- Rates are fractions between 0 and 1, rounded to 4 decimal places.
- Percentages (fields ending in `Pct`) are 0–100, rounded to 2 decimal places.
- NaN and infinity become `null`.

## Attribution and time zones

Each type of data is assigned to a branch as follows:

| Data | Branch column |
|---|---|
| Attendance | `attendance.branch_id` |
| Revenue | `invoices.branch_id` (the plan comes from invoice → subscription → plan) |
| Members and subscriptions | `members.home_branch_id` |
| Trainers | `trainers.home_branch_id` |
| Complaints | `complaints.branch_id` |

Soft-deleted members, branches and trainers are excluded.

Time zones:

- Attendance (series, weekday and peak hours) is converted to each branch's `gym_branches.timezone` before bucketing.
- All other date bucketing (payments, refunds, registrations, sessions, cancellations, last-visit windows) uses `BUSINESS_TIMEZONE`.

## Metric definitions

### Subscription terms (`app/services/subscriptions.py`)

- **Committed** subscription: status `SCHEDULED`, `ACTIVE`, `FROZEN`, `GRACE`, `EXPIRED` or `SUPERSEDED`, or a `CANCELLED` subscription with `activated_at` set. `PENDING_PAYMENT` subscriptions are always ignored.
- **Effective end** (the last day of access):
  - Cancelled subscriptions: `min(end_date, cancelled_at local date)`.
  - Superseded subscriptions: `min(end_date, successor start − 1 day)`.
  - All other subscriptions: `end_date`.
- **Current access** (as of today):
  - `ACTIVE` or `FROZEN` with `start_date ≤ today ≤ end_date`, or
  - `GRACE` with `start_date ≤ today`.

### Attendance

- **Average duration** = `check_out − check_in`, averaged over visits that have a genuine check-out. Visits with `auto_checked_out` and visits that are still open are excluded.
- **Heatmap cell** (weekday d, local hour h) = check-ins in that cell ÷ the number of weekday-d dates in the range.
- **Occupancy** (single branch only):
  - Each visit's interval is split across the hourly slots it overlaps. The overlapping time is summed per slot, which gives the average head-count for that slot.
  - Each hour of the day is then averaged over every day in the range; days without visits count as 0.
  - For visits without a genuine check-out, the duration is assumed to be the median genuine visit duration (60 minutes if there is none), never longer than a recorded check-out.
  - Utilisation % = occupancy ÷ capacity × 100.

### Revenue

Revenue is cash-basis.

- **Gross** = amounts of payments with status `SUCCEEDED`, `PARTIALLY_REFUNDED` or `REFUNDED`, dated by `processed_at`.
- **Refunds** = `SUCCEEDED` refunds, dated by the refund's own `processed_at`.
- **Net** = gross − refunds.
- **Failed-payment rate** = `FAILED` attempts ÷ all attempts.
- **Average revenue per paying member** = net ÷ distinct members with at least one successful payment.
- Payments whose invoice has no subscription are reported under plan `null` with the name "Unassigned".

### Members

- **Active members** = members with current access.
- **Engagement** covers members with current access only, based on check-ins in the last 30 days (including today):
  - HIGHLY_ENGAGED: 12 or more
  - REGULAR: 4–11
  - LOW: 1–3
  - INACTIVE: 0
- **Retention cohort** (registration month C, offset k) = the share of cohort members with a committed subscription whose start date to effective end overlaps month C+k.

### Memberships (per calendar month M; future months are not reported)

**Renewal rate** = renewed ÷ ended (`null` if nothing ended).

- *Ended* = subscriptions with status `ACTIVE`, `FROZEN`, `GRACE`, `EXPIRED` or `SUPERSEDED` whose `end_date` falls in M and is on or before today.
- *Renewed* = ended subscriptions that a committed subscription points to through `previous_subscription_id`.

**Churn rate** = churned ÷ members at start (0 when there are no members at start).

- *Members at start* = distinct members with committed access on the first day of M.
- *Churned* = members at start whose latest committed term ends in M and is not continued. A term is continued when another committed subscription starts no later than effective end + the ending plan's `grace_period_days` + 1, and runs past the effective end.
- Members whose grace window is still open today are reported as `pendingMembers` and are not counted as churned.

**Other membership figures:**

- Plan mix and upcoming expiries are measured as of today.
- Upcoming expiries are cumulative windows of 30, 60 and 90 days. Each window is split into subscriptions that already have a committed follow-on subscription and those that do not. A follow-on is either linked through `previous_subscription_id` or is any later-starting committed subscription of the same member.
- Freeze usage per month counts the overlap of `frozen_from`–`frozen_until` with the month.
- Cancellations are counted by the local date of `cancelled_at`.

### Trainers

Sessions are bucketed by the local date of `starts_at`.

- **Completion rate** = COMPLETED ÷ (COMPLETED + CANCELLED + NO_SHOW).
- **No-show rate** = NO_SHOW ÷ the same denominator.
- **Booked hours** = hours of COMPLETED and SCHEDULED sessions.
- **Available hours** = weekly availability slots × the number of matching weekdays in the range.
- **Utilisation %** = booked hours ÷ available hours × 100.
- **Active clients** = current `ACTIVE` assignments in `member_trainers`.
- **Average client visits** = check-ins in the range by those clients ÷ number of clients.

### Branches

The branch comparison reports, per branch:

- Active members and new members.
- Visits, and visits per active member.
- Net revenue, and revenue per active member.
- Peak hourly average occupancy against capacity.
- Complaints currently `OPEN`, `IN_PROGRESS`, `ESCALATED` or `REOPENED`.
- Rank by net revenue (1 is the highest; tied branches share the better rank).

## Churn-risk rules (no machine learning)

All weights and thresholds are defined in one place: `app/services/churn_risk.py` (`RULES` plus the threshold constants). Only members with a current `ACTIVE` or `GRACE` subscription are scored.

| Rule | Points | Fires when |
|---|---|---|
| NO_VISIT_30_DAYS | 35 | 30 or more days since the last visit (replaces the 14-day rule) |
| NO_VISIT_14_DAYS | 20 | 14 or more days since the last visit |
| VISIT_FREQUENCY_DROP | 20 | Visits in the last 30 days are below 50% of the average per 30 days over the previous 60 days, and that average is at least 2 |
| EXPIRING_WITHOUT_RENEWAL | 25 | The subscription ends within 14 days, or is already in `GRACE`, and no committed follow-on exists |
| RECENT_FAILED_PAYMENT | 15 | A `FAILED` payment in the last 30 days |
| OPEN_COMPLAINT | 10 | An open complaint raised in the last 60 days |
| FROZEN_THIS_TERM | 10 | `freeze_count > 0` or `freeze_days_used > 0` on the current subscription |

- The score is the sum of the triggered rules' points, capped at 100.
- Bands: HIGH is 60 or more, MEDIUM is 30 or more, otherwise LOW.
- Members who have never visited are measured from the start date of their current subscription.
- Each item lists its triggered rules with a description that a person can read.
- Results are sorted by score, then by days since the last visit.

## Security

The JWT contract matches the Java service:

- Tokens arrive as `Authorization: Bearer <JWT>`, signed with HS256.
- The secret is `JWT_SECRET` as raw UTF-8 and must be at least 32 bytes. The service refuses to start with a shorter secret: the container's preflight entrypoint exits with code 1 before uvicorn starts.
- `iss` must equal `JWT_ISSUER`, `aud` must equal `gymops-api`, and `typ` must equal `access`.
- `exp`, `iat` and `sub` are required. A 30-second clock leeway is allowed.

Roles:

- `SUPER_ADMIN` and `GYM_ADMIN` see every branch.
- `MANAGER` sees only the branches listed in the `branches` claim:
  - Passing another branch's `branchId` returns 403.
  - Omitting `branchId` restricts the results to the manager's branches.
- Any other role returns 403.
- A `ROLE_` prefix on role names is tolerated.

Errors use the core service's shape:

```json
{"timestamp": "...", "status": 403, "error": "Forbidden", "code": "ACCESS_DENIED",
 "message": "...", "path": "/analytics/revenue", "correlationId": "..."}
```

Error codes:

- `UNAUTHORIZED` (401)
- `ACCESS_DENIED` (403)
- `VALIDATION_FAILED` (400)
- `ANALYTICS_UNAVAILABLE` (503, when the database cannot be reached or a query times out)
- `INTERNAL_ERROR` (500)
- `NOT_FOUND` (404)

The correlation ID is read from `X-Request-Id` or `X-Correlation-Id`; if neither is present, a UUID is generated. It is returned in `X-Request-Id` and included in every JSON log line, which is written to stdout.

## Caching and staleness

- Computed responses are cached in-process with a TTL cache (`cachetools`).
- The cache key is the endpoint plus the normalised parameters plus the resolved branch scope, so callers with different access never share an entry.
- The TTL is `CACHE_TTL_SECONDS` (default 300); setting it to 0 disables the cache. Figures can therefore be up to about 5 minutes stale.
- Each uvicorn worker keeps its own cache.
- Churn-risk caches the full scored list once per scope; pagination and band filtering are applied on every request.
- Cache hits and misses are exported as `gymops_analytics_cache_events_total`.

## Configuration (environment variables)

| Variable | Default | Notes |
|---|---|---|
| `DATABASE_URL` | required | `postgresql+psycopg://gym_analytics_ro:...@host:5432/db` (a `postgresql://` URL is rewritten to use psycopg) |
| `JWT_SECRET` | required | At least 32 bytes; must be the same as the core service's |
| `JWT_ISSUER` | `gymops-core` | |
| `CACHE_TTL_SECONDS` | `300` | |
| `LOG_LEVEL` | `INFO` | |
| `BUSINESS_TIMEZONE` | `Asia/Kolkata` | IANA name |
| `DB_STATEMENT_TIMEOUT_MS` | `15000` | Applied through connection options, together with `TimeZone=UTC` |
| `DB_POOL_SIZE` / `DB_MAX_OVERFLOW` / `DB_CONNECT_TIMEOUT_SECONDS` | `5` / `5` / `5` | |

The engine uses `pool_pre_ping` and sets `application_name=gymops-analytics`.

## Running

```bash
python3.12 -m venv .venv && . .venv/bin/activate
pip install -r requirements-dev.txt
ruff check .
pytest -q                      # no database needed: repositories are replaced by in-memory fakes
pytest -q --cov=app            # with coverage

docker build -t gymops-analytics:dev .
docker run -p 8000:8000 -e DATABASE_URL=... -e JWT_SECRET=... gymops-analytics:dev
```

About the Docker image:

- It is multi-stage, based on `python:3.12-slim`, and runs as the non-root user `app` (uid 10001).
- It contains no build tools.
- Its HEALTHCHECK calls `/health` using Python.
- It runs 2 uvicorn workers. Prometheus metrics are aggregated across them through `PROMETHEUS_MULTIPROC_DIR=/tmp/prometheus`. That directory is not cleared if the same container is restarted, so counters carry over until the container is recreated.

About the tests:

- `tests/test_*_service.py` contains unit tests for every transform, using hand-built frames with known answers (including time zone conversion).
- `tests/test_security.py` covers the JWT contract and branch scoping.
- `tests/test_api.py` contains TestClient tests that replace the repository dependencies with fakes.
- The SQL in the repositories is not covered by the automated tests.
