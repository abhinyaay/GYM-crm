# GymOps roadmap: what to add next

The core platform covers memberships, billing, access, trainers, workouts, support and analytics. This document lists what a commercial gym product would add next. Items are prioritised by business value and by how well they fit the existing architecture.

Effort is a rough guide: **S** is a few days, **M** about 1–2 weeks, **L** a few weeks.

> **Status:** items 1 (group classes and bookings), 2 (recurring billing with autopay and dunning) and 4 (leads and trial funnel) are now **implemented**; see the README. Each is kept below with a note on what was built and what is still open. Also shipped alongside them: hosted checkout for card / UPI / net banking / wallets / card EMI (Razorpay adapter plus a signed simulator), saved payment methods, store credit, and refunds to store credit.

---

## Tier 1: highest value, natural next steps

### 1. Group classes and bookings (L): implemented (see README)
Most gyms sell classes (yoga, HIIT, spin) as much as floor access.

- **Model:** `class_types`, `class_sessions` (room, instructor = trainer, capacity, branch, recurrence rule) and `class_bookings`. Booking statuses are BOOKED, WAITLISTED, CANCELLED, ATTENDED and NO_SHOW.
- **Rules:**
  - Booking window, e.g. open 7 days ahead and close 1 hour before.
  - Late-cancellation and no-show penalties.
  - Plan entitlements, e.g. "8 classes/month" or "unlimited".
  - **Waitlist auto-promotion** when someone cancels.
- **Reuses existing pieces:**
  - The same PostgreSQL techniques as check-in (row lock on the session plus a capacity check, partial unique index per member and session).
  - The outbox for "you got a spot" notifications.
  - Check-in linkage (attending a class counts as a visit).
- **Analytics:** class fill rate, waitlist demand and instructor popularity in the Python service.

### 2. Recurring billing with autopay and dunning (L): implemented
**Built:**
- card / UPI tokens with a mandate set up during checkout;
- hourly autopay job: renewal raised 3 days before the term ends, 24-hour pre-debit notice, merchant-initiated debit;
- retries after 1, 2 and 3 days, then autopay switches off;
- signed, de-duplicated webhooks with intent-then-confirm settlement.

**Still open:** UPI Autopay's own mandate-approval flow and e-NACH; moving provider calls out of the database transaction into an outbox worker; and suspending (rather than lapsing) members after dunning.

The original proposal follows. Today every renewal is paid manually. Real gyms run on autopay.

- **Mandates:** store a gateway mandate or token (Razorpay Subscriptions, UPI Autopay / e-NACH in India, or Stripe).
- **Auto-charge:** the daily lifecycle job already finds renewals due. It would create the renewal and charge the stored mandate, with the same idempotency key scheme.
- **Dunning:** on failure, retry after 1, 3 and 7 days, notify at each step, and move to GRACE, then SUSPENDED.
- **Webhooks:** the payment provider confirms asynchronously, so add `POST /webhooks/payments/{provider}` with signature verification and an idempotent event store. This also removes the current trade-off of calling the gateway inside the database transaction (intent → webhook confirm).
- **Touches:** `PaymentGateway` port (new adapter), `payments` (new PENDING status), `SubscriptionLifecycleJob`.

### 3. Real notification channels: WhatsApp, SMS, email (M)
The outbox and `NotificationSender` interface are already built for this.

- **Senders:**
  - email: SES or SendGrid;
  - SMS: MSG91 or Twilio;
  - **WhatsApp Business API**, the dominant channel for Indian gyms.
- **Templates:** per notification type, with localisation (English, Hindi …).
- **Member preferences:** channel opt-in or opt-out per type, which is also a legal requirement (see item 10).
- **Delivery receipts** via provider webhooks, which update the existing notification status.

### 4. Leads and trial funnel (CRM) (M): implemented (see README)
Revenue starts before someone becomes a member.

- **Leads:** `leads` with source (walk-in, website, Instagram, referral), status pipeline (NEW → CONTACTED → TOUR_BOOKED → TRIAL → CONVERTED or LOST), owner (staff) and follow-up tasks with due dates.
- **Trial passes:** the trial plan already exists; it would get a "trial for lead" flow.
- **Conversion:** reuses member registration, keeping the lead → member link for attribution.
- **Analytics:** funnel conversion by source and by staff member.

### 5. Retention automation driven by churn risk (M)
The analytics service already scores churn risk with explainable reasons. Close the loop:

- **Nightly playbook:** HIGH-risk members get a task for the front desk or their trainer ("call: no visit in 40 days") and/or an automated "we miss you" message with an optional discount code. Discount codes already exist.
- **Measurement:** compare churn between contacted and not-contacted members, using a small hold-out group.
- **Design:** the Python service stays read-only. It exposes the list, and the Java core runs the actions through a scheduled job plus the outbox.

### 6. Member mobile experience (M → L)
- **Installable web app:** turn the web frontend into a PWA with an offline shell.
- **QR check-in:** a rotating, signed QR token (TOTP-style, valid ~30 s) shown in the app and scanned at the turnstile. The backend already supports the `QR_CODE` check-in method; it needs a `POST /attendance/qr-token` and verification at the reader.
- **Push notifications** (FCM / Web Push) as another `NotificationSender`.

---

## Tier 2: strong additions

| Feature | What / why | Effort |
|---|---|---|
| **PT packages & session credits** | Sell "10 PT sessions" bundles; booking a session consumes a credit; expiring credits; trainer commission per completed session | M |
| **Family / corporate memberships** | Payer ≠ member; corporate invoicing with GSTIN; family discounts; one invoice, many members | M |
| **Point of sale & inventory** | Supplements, merchandise, locker rentals; stock levels and low-stock alerts; add to the member's invoice | M |
| **Digital waivers & contracts** | Health declaration + terms signed at registration (e-sign), versioned and stored; required before first check-in | S–M |
| **Access-control hardware** | Turnstile / RFID integration: a small edge service calling the check-in API (or offline allow-list cache for outages) | M |
| **Referral programme** | Referral codes, reward credits when the referee's first payment succeeds (an existing domain event) | S |
| **Staff scheduling & payroll inputs** | Shifts per branch, attendance of staff, trainer commission reports exported to payroll | M |
| **Equipment & maintenance** | Asset register per branch; complaints of category EQUIPMENT link to an asset; preventive maintenance schedules | S–M |
| **Exports & reports** | CSV/Excel/PDF export of members, invoices, GST summary (GSTR-1 friendly), attendance; scheduled email reports to managers | S |
| **GST e-invoicing** | B2B invoices with GSTIN, HSN/SAC codes, IRN/QR from the government portal for corporate clients | M |
| **Body-composition devices & wearables** | Import InBody/scale measurements into `member_progress`; Google Fit / Apple Health sync | M |

## Tier 3: analytics and data

- **Occupancy forecasting.** Predict the next 7 days of hourly visits per branch. There is a year of attendance with strong weekly seasonality, so a simple seasonal model (e.g. Prophet or SARIMA) is a *legitimate* use of ML here, unlike churn, where there are no outcome labels yet. It would drive staffing and "best time to visit" hints in the member app.
- **Churn model v2.** Once 6–12 months of churn outcomes have been collected (actual EXPIRED-without-renewal), train a calibrated model and compare it with the rule-based score. Keep the reasons explainable (e.g. SHAP).
- **Revenue analytics.** MRR / ARR, net revenue retention, LTV by acquisition source (needs leads, item 4) and plan-mix forecasting.
- **Data platform.** Point analytics at an RDS read replica. Then add nightly ELT into a warehouse (Redshift or BigQuery) with dbt models when history outgrows the OLTP database.

## Tier 4: platform and engineering

| Area | Improvement |
|---|---|
| **Security** | TOTP 2FA for staff; SSO (Google Workspace / Azure AD) for staff via OIDC; move the refresh token to an httpOnly cookie through a small BFF; RS256 + JWKS so analytics can only verify tokens; per-user API rate limits |
| **Multi-tenancy (SaaS)** | Serve many gym companies: `tenant_id` on every table + PostgreSQL row-level security, tenant-aware JWT claim, per-tenant branding & plans. This is the step from "a gym's system" to "a gym software company" |
| **Event streaming** | Publish the existing domain events to SQS/SNS or Kafka via the outbox (outbox relay), enabling integrations and async consumers without coupling |
| **Search** | Member search via PostgreSQL trigram indexes (`pg_trgm`) now; OpenSearch only if needed |
| **Observability** | OpenTelemetry tracing across nginx → core → analytics; Grafana dashboards on the Prometheus metrics that already exist; SLOs (check-in p95 < 300 ms, payment success rate) with burn-rate alerts |
| **Frontend hosting** | Serve the SPA from S3 + CloudFront (with the same CSP) instead of a container; add Playwright end-to-end tests to CI |
| **Data lifecycle** | Partition `attendance` and `audit_logs` by month; archive old partitions to S3 |
| **API** | Public partner API with API keys and webhooks (for aggregators like ClassPass/Cult-style partners) |

## Compliance (India-specific, important before launch)

- **DPDP Act 2023 (India's data protection law).** Each item below needs to be designed in:
  - consent records for marketing notifications;
  - a privacy notice;
  - a data-access export ("download my data");
  - erasure on request. Soft-deletion exists; anonymisation of personal fields would need to be added.
  - a grievance officer workflow (the complaints module can host this);
  - breach logging.
- **GST.** Tax rate per plan / HSN, B2B invoices with GSTIN, credit notes for refunds. Today a refund updates the invoice; a proper **credit note document** is the compliant form.
- **Payments.** PCI-DSS scope stays minimal as long as only gateway tokens are handled, which is the current design. Keep it that way.

---

## Suggested sequence

1. ~~Recurring billing + webhooks (item 2)~~ (done) and **real notification channels** (item 3). These turn the system into something a gym can run its revenue on.
2. ~~Classes & bookings (item 1)~~ (done).
3. ~~Leads/trials (item 4)~~ (done) + **retention automation** (item 5). Growth and retention loops built on data the platform already has.
4. **Mobile QR check-in / PWA** (item 6) + **waivers**.
5. **Compliance hardening** (DPDP, GST credit notes) before onboarding a paying customer.
6. **Multi-tenancy**, if the goal is to sell the software to many gyms.
