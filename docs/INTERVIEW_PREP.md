# Interview preparation: GymOps

Every answer below refers to code that exists in this repository. File references let you open the implementation while you talk.

---

### 1. Why Spring Boot?
The core of this system is transactional: checkout, refunds, freezes and check-ins all write several tables that must stay consistent. Spring Boot provides:

- **Transactions.** Declarative `@Transactional` with propagation control. I use `MANDATORY` so that audit and outbox writes can only join an existing business transaction (`AuditService`, `NotificationService`).
- **Persistence.** JPA/Hibernate with optimistic (`@Version`) and pessimistic (`@Lock(PESSIMISTIC_WRITE)`) locking.
- **Security.** Spring Security with method-level `@PreAuthorize`.
- **Validation.** Bean Validation on request records.
- **Operations.** Actuator health groups and Micrometer metrics.
- **Ecosystem.** Flyway, Redis caching, ShedLock and Resilience4j, all through auto-configuration.

Java 21 records keep DTOs immutable and concise. The alternative, hand-wiring all of this, would cost weeks and add risk with no benefit.

### 2. Why PostgreSQL?
Several business invariants are enforced *by the database*, and PostgreSQL has features most databases lack:

- **GiST exclusion constraints.** These forbid overlapping subscription date ranges per member (`ex_subscriptions_no_overlap`) and double-booked trainer or member sessions (`tstzrange`).
- **Partial unique indexes.** One open visit per member, one unpaid subscription per member, one settled payment per invoice, one active trainer and one active workout plan per member, and soft-delete-aware unique emails.
- **`FOR UPDATE SKIP LOCKED`.** Lets the notification outbox be drained by many instances at once.
- **Other types.** `jsonb` for audit details, `numeric` for money, and sequences for human-readable invoice, member and ticket numbers.

On top of that, PostgreSQL gives strong ACID guarantees, is mature on RDS (Multi-AZ, point-in-time recovery), and is easy to read from Pandas for analytics.

### 3. Why Redis?
Redis has three specific jobs, and it is **never** the source of truth:

1. **Cache** for the public plan catalogue (`GET /plans`, the most-read endpoint), member profiles (front-desk lookups) and the admin dashboard (60 s TTL). See `CacheConfig`.
2. **Distributed rate limiting** on login, register and refresh, so the limit holds across all instances. It is an atomic Lua `INCR` plus `PEXPIRE` (`RateLimiter`).
3. A shared, low-latency store that every stateless app instance sees.

I deliberately did *not* put idempotency keys or sessions in Redis. Payment idempotency must be durable, so it lives in PostgreSQL. Sessions are not needed because authentication is a stateless JWT.

### 4. Why Python/FastAPI?
Analytics is dataframe work: group-bys over time buckets, cohort retention matrices, 7×24 heatmaps converted to each branch's time zone, rolling comparisons. Pandas expresses these in a few lines that can be unit-tested. In Java the same logic would be pages of collectors or complex SQL.

FastAPI adds typed request and response models (Pydantic), automatic OpenAPI, dependency injection (repositories are injected, so tests swap in fakes) and good performance under uvicorn.

### 5. Why a separate analytics service?
- **Different workload.** Long scans and aggregations should not compete with short OLTP transactions in the same JVM thread and connection pools.
- **Least privilege.** It connects as `gym_analytics_ro`, which is SELECT-only, has `default_transaction_read_only`, a statement timeout, and no access to password hashes or token tables. A bug in analytics cannot corrupt billing.
- **Independent scaling and deployment.** Analytics evolves faster, with new metrics weekly, and scales differently.
- **Failure isolation.** If it is down, the core keeps working (see Q14).

It does *not* duplicate CRUD. It has no write endpoints at all.

### 6. Why not build analytics in Java?
Simple operational counts *are* in Java. `DashboardQueries` produces today's check-ins, pending invoices and this month's revenue: each is one indexed aggregate that front-desk screens need in real time.

The *analytical* questions belong in Python: retention cohorts, renewal and churn rates over time, peak-hour heatmaps in local time, trainer utilisation against expanded availability, and churn-risk scoring. They need dataframe operations and fast iteration, and Pandas is the industry tool for that.

Keeping them out of the core also keeps the transactional codebase small and its release risk low. The division is explicit in the README (§11).

### 7. How does subscription renewal work?
Code: `SubscriptionService.renew`.

1. Lock the member row (`SELECT … FOR UPDATE`), so concurrent renew, upgrade or freeze calls for the same member queue up.
2. Validate that the source subscription is SCHEDULED, ACTIVE, FROZEN, GRACE or EXPIRED, that it hasn't already been renewed or changed (no open successor), and that no unpaid subscription exists.
3. Choose the start date. For a continuing membership it is `endDate + 1`, even during grace, so membership stays continuous. For an EXPIRED one it is today.
4. Insert a `PENDING_PAYMENT` subscription linked by `previous_subscription_id`, and issue the invoice (discount, GST), all in one transaction.
5. Payment activates it. A future start becomes `SCHEDULED`; the daily job starts it on the start date and moves the predecessor straight to `EXPIRED`, skipping grace.

The job also queues renewal reminders 7 days before the end and an expiry warning 1 day before, de-duplicated by key. A test advances a mutable clock through grace and expiry and runs the job (`MembershipLifecycleIT`).

### 8. How do you prevent duplicate payments?
There are four layers (`PaymentService`):

1. **Idempotency-Key header (required).** The key is stored with a SHA-256 hash of the request.
   - A retry with the same key and the same request returns the original payment (`200`, `Idempotent-Replayed: true`) without calling the gateway again.
   - The same key with a different request is rejected (`422`).
2. **Row locks.** The member row, then the invoice row, are locked *before* the key lookup, so two concurrent retries cannot both miss the lookup.
3. **Business check.** The invoice must still be `ISSUED`; otherwise the request gets `409`, even with a fresh key.
4. **Database backstops.** `UNIQUE(idempotency_key)` and a partial unique index allowing only one settled payment per invoice.

The key is also passed to the gateway, so a real provider de-duplicates on its side. `ConcurrencyIT` fires 12 parallel requests with the same key (exactly one charge) and 12 with different keys (one `201`, eleven `409`).

A declined payment is *recorded* as FAILED, but the client must retry with a new key. This is the same semantics as Stripe.

### 9. How do you prevent duplicate check-ins?
- **Partial unique index.** `ux_attendance_one_open_visit ON attendance(member_id) WHERE check_out_time IS NULL` makes a second open visit impossible, at any branch.
- **Friendly error.** The service locks the member row and checks for an open visit first, so the normal case returns a clear `409 DUPLICATE_CHECK_IN` that says where and since when the member is checked in. A race that slips past would hit the index, which `DatabaseConstraints` also maps to `DUPLICATE_CHECK_IN`.
- **Forgotten check-outs.** A job auto-closes visits after 12 hours, so a forgotten tap-out doesn't block the member forever.

`ConcurrencyIT` sends 12 concurrent check-ins and gets exactly one visit row.

### 10. How is authentication implemented?
- **Login.** Email and password are checked with BCrypt through Spring's delegating encoder (`{bcrypt}` prefix). Five failures lock the account for 15 minutes. Unknown emails still run a BCrypt comparison against a dummy hash, so response timing doesn't reveal which accounts exist.
- **Access token.** An HS256 JWT valid for 15 minutes, carrying user ID, roles, branch IDs, memberId and trainerId. Issuer, audience and type are validated (`JwtService`). Each request is authenticated statelessly (`JwtAuthenticationFilter`).
- **Refresh token.** A random 256-bit value valid for 14 days. Only its SHA-256 hash is stored. It **rotates** on every use. Reusing an old token, which suggests theft, revokes the whole family (`AuthService.refresh`).
- **Revocation.** Logout and logout-all revoke refresh tokens. So do password change, account disable, deletion and role change.
- **Rate limits.** Per-IP Redis rate limit on authentication endpoints, plus Nginx edge limits.

Secrets come only from environment variables or Secrets Manager. The app refuses to start if the JWT secret is under 32 characters (validated `@ConfigurationProperties`).

### 11. How does RBAC work?
Authorization has two layers:

- **Role layer.** `@PreAuthorize` on controller methods answers "may this role call this endpoint?". For example, only SUPER_ADMIN and GYM_ADMIN create plans, and only managers refund.
- **Data layer.** `AccessPolicy` answers "may this caller touch *this* branch or member?". SUPER_ADMIN and GYM_ADMIN are org-wide. MANAGER, RECEPTIONIST and TRAINER are limited to the branches in their token. A MEMBER only sees their own records. List endpoints apply the same scope as a SQL filter (`BranchScope`), so nothing is filtered after the fact in memory.

Privilege escalation is blocked: a GYM_ADMIN cannot grant admin roles (`UserService`).

The analytics service enforces the same model independently from the same JWT claims. Tests cover 401, 403, branch scoping and escalation (`MemberControllerTest`, `SecurityIT`).

### 12. How would the system scale?
- **Stateless core.** JWT authentication, with rate-limit and cache state in Redis. ECS auto-scales on CPU and requests per target.
- **Jobs.** Scheduled jobs run once per cluster via ShedLock. The outbox dispatcher scales out via `SKIP LOCKED`.
- **Database.** Indexes match the query paths. Next steps, in order:
  - RDS Proxy or PgBouncer as task count grows;
  - a read replica for analytics (only its `DATABASE_URL` changes);
  - monthly partitions for `attendance` and `audit_logs`;
  - precomputed daily aggregates or a warehouse for multi-year analytics.
- **Notifications.** Move the outbox relay to SQS or Kafka when fan-out to many providers is needed.
- **Larger re-architecture.** Only if the team grows: split billing out *behind the existing ports* (`PaymentGateway`, `PaidInvoiceHandler`), with an outbox-based saga.

### 13. What happens if the Python service fails?
Nothing breaks for members or staff:
- The core never depends on analytics for correctness.
- The only call is the admin dashboard's churn figure, which runs under a 500 ms connect / 3 s read timeout and a Resilience4j circuit breaker (`AnalyticsClient`).
- On failure the dashboard still returns `200` with `memberChurn: null` and `degraded: ["memberChurn"]`.

The breaker stops hammering a dead dependency, and the readiness probe doesn't include analytics, so core instances stay in the load balancer.

This was verified twice: by stopping the analytics container in the running stack, and in `MembershipLifecycleIT.dashboardDegradesGracefullyWhenAnalyticsIsDown`.

### 14. What happens if Redis fails?
The platform keeps serving from PostgreSQL:
- **Cache.** `CacheErrorHandler` turns Redis errors into cache misses, logged at WARN.
- **Rate limiter.** It fails *open*; per-account lockout and the Nginx edge limits still bound brute force.
- **Load balancer.** Readiness covers only the database, so instances are not removed. Latency rises and the database takes more load.

This was verified by stopping the Redis container: readiness stayed 200, the plan catalogue and login still worked, and the logs showed "Cache GET failed … falling back to database" and "Rate limiter unavailable, failing open".

ElastiCache runs with a replica and automatic failover to keep such incidents short.

### 15. How would you handle high attendance traffic?
Peaks are predictable, around 06:00–09:00 and 17:00–21:00; the peak-hour analytics shows this.

**Current design:**
- Check-in is one short transaction: a per-member row lock, two or three indexed lookups and one insert. Contention is per member, never global.
- Plan rules are evaluated in memory on the plan already loaded with the member's active subscription.
- Notifications are written to the outbox and sent asynchronously.
- The app scales horizontally behind the ALB.

**Next steps if needed:**
- scheduled scale-out before the peaks;
- RDS Proxy for connection bursts;
- caching the "can enter" decision per member with event-based eviction;
- turnstile devices calling a dedicated, minimal endpoint;
- partitioning `attendance` by month to keep indexes small.

### 16. How would you handle concurrent membership updates?
Concurrency control is layered (`SubscriptionService`):

1. **Member row lock.** `SELECT … FOR UPDATE` on the member serialises every membership-changing operation (subscribe, renew, upgrade or downgrade, freeze, resume, cancel, pay, lifecycle job) for that member. Different members never block each other.
2. **Fixed lock order** (member → invoice → payment) avoids deadlocks.
3. **Optimistic locking.** `@Version` catches lost updates from any path that isn't locked; the client gets `409 CONCURRENT_MODIFICATION` and retries.
4. **Database constraints.** The exclusion constraint and partial unique indexes guarantee the invariants even if application code had a bug.
5. **Ordered flushes.** Changes that must hit the database in order use explicit `saveAndFlush`: supersede before activating an upgrade, and shift queued renewals latest-first before extending a frozen term.

`ConcurrencyIT` runs 12 simultaneous renew and upgrade calls and gets exactly one winner. It also runs 12 simultaneous freezes and the freeze applies exactly once.

### 17. How would you monitor production?
- **Golden signals.** ALB request count, 5xx, p95 latency and unhealthy targets, with CloudWatch alarms to SNS (`infra/aws/terraform/monitoring.tf`).
- **App metrics** via Prometheus (`/actuator/prometheus`, internal only):
  - HTTP latency histograms, JVM, Hikari pool saturation, cache hit/miss, circuit-breaker state;
  - business counters: payments by status and method, idempotent replays, refunds, check-ins by branch, denied check-ins by reason, notification delivery results.

  A rising denial rate or failed-payment rate is often the first sign of a business incident.
- **Logs.** JSON logs in CloudWatch, with a metric filter that alarms on ERROR bursts. One correlation ID connects Nginx/ALB, core and analytics log lines, plus the `correlationId` field in every error response, which customer support can quote.
- **Data tier.** RDS CPU, storage and Performance Insights, the slow-query log (over 500 ms), and Redis memory.
- **Outbox health.** `GET /api/v1/notifications/admin?status=FAILED`, with retry.
- **Deployments.** Health-gated rolling deploys with an automatic circuit-breaker rollback, and post-deploy checks in the pipeline.

---

## Extra questions you may get

**How do upgrades and downgrades work?**
- An **upgrade** is a new plan with a higher contract value. It takes effect on payment. The unused paid days of the current term become a `PRORATION_CREDIT` invoice line (`CancellationRefundCalculator.upgradeCredit`), and the old term becomes `SUPERSEDED` and is flushed before the new term is activated, so the exclusion constraint never sees an overlap.
- A **downgrade** starts at the next term with no credit.

**How is a cancellation refund computed?**
It depends on the plan's policy:
- `NON_REFUNDABLE`: 0.
- `PRORATED`: `paid × unusedPaidDays / paidDays − fee`.
- `COOLING_OFF_FULL_REFUND`: everything still refundable within N days of activation, prorated after that.

Frozen days are free days, not paid days. The refund is capped at the refundable balance and processed idempotently with the key `cancel-subscription-{id}`. The integration test asserts an exact figure: ₹2,260.00 on ₹3,540 paid with 20 of 30 days unused and a ₹100 fee.

**Why an outbox instead of sending email in the service?**
Sending inside the transaction creates a dual-write problem. If the email goes out and the transaction then rolls back, the customer is told about a payment that never happened. If the transaction commits and the send fails, the notification is lost. The outbox commits the intent atomically with the change, and delivery is retried later.

**Why snapshot the plan price on the subscription?**
So that raising prices never changes what existing members paid or will be refunded. Invoices and refunds stay historically correct.

**How do you test time-dependent logic?**
Everything uses an injected `Clock`. Unit tests use fixed dates. Integration tests use a `MutableClock` bean and call `advance(...)` to move through grace periods, expiry and cooling-off windows.

**What would you change first for a real launch?**
- a real payment provider with webhooks, and the intent → confirm flow so the gateway call is no longer inside the database transaction;
- email verification and password reset;
- RS256 with JWKS;
- an analytics read replica;
- table partitioning;
- a Prometheus/Grafana stack.

These are listed in README §21.
