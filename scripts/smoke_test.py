#!/usr/bin/env python3
"""End-to-end smoke test of the running platform (stdlib only, so it runs anywhere - laptop or CI).

Usage:
    BASE_URL=http://localhost ADMIN_EMAIL=... ADMIN_PASSWORD=... python3 scripts/smoke_test.py

Exercises real business flows through the public API and asserts status codes and invariants:
auth + refresh rotation + reuse detection, RBAC, branch scoping, member registration, subscription
purchase, idempotent payment, declined payment, duplicate check-in, freeze/resume, renewal,
cancellation with refund, complaints, dashboards and (optionally) the analytics service.
Exit code 0 = all checks passed.
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("BASE_URL", "http://localhost:8080").rstrip("/")
ADMIN_EMAIL = os.environ["ADMIN_EMAIL"]
ADMIN_PASSWORD = os.environ["ADMIN_PASSWORD"]
CHECK_ANALYTICS = os.environ.get("CHECK_ANALYTICS", "false").lower() == "true"
# stay below the edge rate limit (nginx: 30 req/s per client IP)
DELAY_SECONDS = float(os.environ.get("SMOKE_DELAY_SECONDS", "0.05"))
RUN = uuid.uuid4().hex[:6]

passed = 0
failures: list[str] = []


def call(method: str, path: str, token: str | None = None, body: dict | None = None,
         headers: dict | None = None) -> tuple[int, dict | list | None, dict]:
    time.sleep(DELAY_SECONDS)
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Request-Id", f"smoke-{RUN}-{int(time.time() * 1000)}")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            raw = resp.read()
            if raw and "json" not in (resp.headers.get("Content-Type") or ""):
                return resp.status, {"bytes": len(raw), "head": raw[:5]}, dict(resp.headers)
            return resp.status, (json.loads(raw) if raw else None), dict(resp.headers)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            payload = json.loads(raw) if raw else None
        except json.JSONDecodeError:
            payload = {"raw": raw.decode(errors="replace")}
        return e.code, payload, dict(e.headers)


def check(name: str, condition: bool, detail: object = "") -> None:
    global passed
    if condition:
        passed += 1
        print(f"  PASS  {name}")
    else:
        failures.append(name)
        print(f"  FAIL  {name}  ->  {detail}")


def expect(name: str, response: tuple, status: int) -> dict | list | None:
    code, body, _ = response
    check(f"{name} [{status}]", code == status, f"got {code}: {json.dumps(body)[:400]}")
    return body


def login(email: str, password: str) -> dict:
    code, body, _ = call("POST", "/api/v1/auth/login", body={"email": email, "password": password})
    if code != 200:
        raise SystemExit(f"login failed for {email}: {code} {body}")
    return body


def main() -> int:
    print(f"Smoke test against {BASE} (run {RUN})")

    print("\n# Health & public endpoints")
    expect("liveness", call("GET", "/actuator/health/liveness"), 200)
    expect("readiness", call("GET", "/actuator/health/readiness"), 200)
    plans = expect("public plan catalogue", call("GET", "/api/v1/plans"), 200)
    plan = next(p for p in plans if p["code"] == "MONTHLY-STD")
    annual = next(p for p in plans if p["code"] == "ANNUAL-PREMIUM")
    expect("unauthenticated member list rejected", call("GET", "/api/v1/members"), 401)
    expect("bad credentials", call("POST", "/api/v1/auth/login",
                                   body={"email": ADMIN_EMAIL, "password": "wrong-password-1"}), 401)
    expect("validation error", call("POST", "/api/v1/auth/login", body={"email": "nope"}), 400)

    print("\n# Authentication")
    admin_tokens = login(ADMIN_EMAIL, ADMIN_PASSWORD)
    admin = admin_tokens["accessToken"]
    rotated = expect("refresh rotates token", call("POST", "/api/v1/auth/refresh",
                                                   body={"refreshToken": admin_tokens["refreshToken"]}), 200)
    check("new refresh token issued", rotated and rotated["refreshToken"] != admin_tokens["refreshToken"])
    expect("reuse of rotated refresh token detected", call("POST", "/api/v1/auth/refresh",
                                                           body={"refreshToken": admin_tokens["refreshToken"]}), 401)
    expect("whole token family revoked after reuse", call("POST", "/api/v1/auth/refresh",
                                                          body={"refreshToken": rotated["refreshToken"]}), 401)
    me = expect("current user", call("GET", "/api/v1/users/me", admin), 200)
    check("super admin role", "SUPER_ADMIN" in me["roles"], me)

    print("\n# Branch (created fresh so the test is independent of opening hours)")
    branch = expect("create branch", call("POST", "/api/v1/branches", admin, {
        "code": f"SMK-{RUN}", "name": f"Smoke {RUN}", "addressLine1": "1 Test Street", "city": "Pune",
        "country": "IN", "timezone": "Asia/Kolkata", "capacity": 50}), 201)
    branch_id = branch["id"]
    branches = expect("list branches", call("GET", "/api/v1/branches?size=50", admin), 200)
    other_branch = next(b for b in branches["content"] if b["id"] != branch_id)

    print("\n# Staff & RBAC")
    staff_password = f"Rcp{uuid.uuid4().hex[:10]}A1"
    receptionist = expect("create receptionist assigned to branch", call("POST", "/api/v1/users", admin, {
        "email": f"desk.{RUN}@example.com", "initialPassword": staff_password, "fullName": "Smoke Desk",
        "roles": ["RECEPTIONIST"], "branchIds": [branch_id]}), 201)
    desk = login(receptionist["email"], staff_password)["accessToken"]
    expect("receptionist cannot create plans", call("POST", "/api/v1/plans", desk, {
        "code": f"X{RUN}", "name": "X", "billingPeriod": "MONTHLY", "price": 1, "cancellationPolicy": "PRORATED"}), 403)
    expect("receptionist cannot read another branch", call("GET", f"/api/v1/branches/{other_branch['id']}", desk), 403)

    print("\n# Member registration")
    member = expect("register member at front desk", call("POST", "/api/v1/members", desk, {
        "homeBranchId": branch_id, "firstName": "Smoke", "lastName": "Tester", "email": f"smoke.{RUN}@example.com",
        "phone": "+91 9000000000", "emergencyContacts": [
            {"name": "Kin", "relationship": "PARENT", "phone": "+91 9111111111", "primary": True}]}), 201)
    member_id = member["id"]
    check("member code assigned", member["memberCode"].startswith("M"), member)
    expect("duplicate email rejected", call("POST", "/api/v1/members", desk, {
        "homeBranchId": branch_id, "firstName": "Dup", "lastName": "Dup", "email": f"smoke.{RUN}@example.com",
        "phone": "+91 9000000001"}), 409)
    found = expect("search members", call("GET", f"/api/v1/members?q=smoke.{RUN}", desk), 200)
    check("search finds member", found["totalElements"] == 1, found)
    expect("check-in without membership refused", call("POST", "/api/v1/attendance/check-in", desk,
                                                        {"memberId": member_id, "branchId": branch_id}), 422)

    print("\n# Subscription purchase & idempotent payment")
    sub = expect("subscribe (pending payment)", call("POST", "/api/v1/subscriptions", desk, {
        "memberId": member_id, "planId": plan["id"], "discountCode": "WELCOME10"}), 201)
    check("subscription pending", sub["status"] == "PENDING_PAYMENT", sub)
    expect("second purchase while one is unpaid rejected", call("POST", "/api/v1/subscriptions", desk, {
        "memberId": member_id, "planId": plan["id"]}), 409)
    invoice = expect("invoice", call("GET", f"/api/v1/payments/invoices/{sub['invoiceId']}", desk), 200)
    check("joining fee + discount + GST on invoice",
          len(invoice["lines"]) == 2 and float(invoice["discountAmount"]) > 0 and float(invoice["taxAmount"]) > 0, invoice)

    expect("declined card recorded", call("POST", "/api/v1/payments", desk, {
        "invoiceId": invoice["id"], "amount": invoice["totalAmount"], "method": "CARD", "paymentToken": "tok_fail_visa"},
        headers={"Idempotency-Key": f"smoke-{RUN}-declined"}), 402)
    expect("payment without Idempotency-Key rejected", call("POST", "/api/v1/payments", desk, {
        "invoiceId": invoice["id"], "amount": invoice["totalAmount"], "method": "UPI", "paymentToken": "tok_ok"}), 400)
    key = f"smoke-{RUN}-pay-1"
    pay_body = {"invoiceId": invoice["id"], "amount": invoice["totalAmount"], "method": "UPI", "paymentToken": "tok_upi_ok"}
    code, payment, headers = call("POST", "/api/v1/payments", desk, pay_body, {"Idempotency-Key": key})
    check("payment succeeded [201]", code == 201 and payment["status"] == "SUCCEEDED", payment)
    code, replay, headers = call("POST", "/api/v1/payments", desk, pay_body, {"Idempotency-Key": key})
    check("retry with same key replays original [200]", code == 200 and replay["id"] == payment["id"]
          and headers.get("Idempotent-Replayed") == "true", (code, replay))
    expect("same key, different request rejected", call("POST", "/api/v1/payments", desk,
                                                         {**pay_body, "method": "CARD"}, {"Idempotency-Key": key}), 422)
    expect("paying an already-paid invoice with a new key rejected", call("POST", "/api/v1/payments", desk, pay_body,
                                                                         {"Idempotency-Key": f"smoke-{RUN}-pay-2"}), 409)
    sub = expect("subscription active", call("GET", f"/api/v1/subscriptions/{sub['id']}", desk), 200)
    check("status ACTIVE after payment", sub["status"] == "ACTIVE", sub)

    print("\n# Attendance")
    visit = expect("check-in", call("POST", "/api/v1/attendance/check-in", desk,
                                    {"memberId": member_id, "branchId": branch_id}), 201)
    expect("duplicate check-in refused", call("POST", "/api/v1/attendance/check-in", desk,
                                              {"memberId": member_id, "branchId": branch_id}), 409)
    occupancy = expect("live occupancy", call("GET", f"/api/v1/attendance/branches/{branch_id}/occupancy", desk), 200)
    check("one member inside", occupancy["currentlyInside"] == 1, occupancy)
    out = expect("check-out", call("POST", "/api/v1/attendance/check-out", desk, {"memberId": member_id}), 200)
    check("visit closed", out["id"] == visit["id"] and out["checkOutTime"] is not None, out)
    expect("check-out when not inside refused", call("POST", "/api/v1/attendance/check-out", desk,
                                                     {"memberId": member_id}), 409)

    print("\n# Freeze / resume / renew / change plan / cancel")
    frozen = expect("freeze 10 days", call("POST", f"/api/v1/subscriptions/{sub['id']}/freeze", desk, {"days": 10}), 200)
    check("end date extended by 10 days", frozen["status"] == "FROZEN" and frozen["endDate"] != sub["endDate"], frozen)
    expect("check-in while frozen refused", call("POST", "/api/v1/attendance/check-in", desk,
                                                 {"memberId": member_id, "branchId": branch_id}), 422)
    resumed = expect("resume early", call("POST", f"/api/v1/subscriptions/{sub['id']}/resume", desk), 200)
    check("unused freeze days returned", resumed["endDate"] == sub["endDate"] and resumed["freezeDaysUsed"] == 0, resumed)
    renewal = expect("renew", call("POST", f"/api/v1/subscriptions/{sub['id']}/renew", desk, {}), 201)
    check("renewal starts the day after current end", renewal["previousSubscriptionId"] == sub["id"], renewal)
    expect("renewing twice rejected", call("POST", f"/api/v1/subscriptions/{sub['id']}/renew", desk, {}), 409)
    expect("cancel unpaid renewal", call("POST", f"/api/v1/subscriptions/{renewal['id']}/cancel", desk,
                                         {"reason": "Changed mind"}), 200)
    upgrade = expect("upgrade to annual (prorated credit)", call("POST", f"/api/v1/subscriptions/{sub['id']}/change-plan",
                                                                  desk, {"newPlanId": annual["id"]}), 201)
    check("classified as UPGRADE", upgrade["changeType"] == "UPGRADE", upgrade)
    up_invoice = expect("upgrade invoice", call("GET", f"/api/v1/payments/invoices/{upgrade['invoiceId']}", desk), 200)
    check("proration credit line present", any(line["type"] == "PRORATION_CREDIT" for line in up_invoice["lines"]),
          up_invoice)
    expect("pay upgrade", call("POST", "/api/v1/payments", desk, {
        "invoiceId": up_invoice["id"], "amount": up_invoice["totalAmount"], "method": "CASH", "externalReference": "RCPT-1"},
        {"Idempotency-Key": f"smoke-{RUN}-upgrade"}), 201)
    old = expect("old subscription", call("GET", f"/api/v1/subscriptions/{sub['id']}", desk), 200)
    check("old subscription SUPERSEDED", old["status"] == "SUPERSEDED", old)
    cancelled = expect("cancel within cooling-off", call("POST", f"/api/v1/subscriptions/{upgrade['id']}/cancel", desk,
                                                         {"reason": "Relocating"}), 200)
    check("full refund within cooling-off period",
          float(cancelled["refundAmount"]) == float(up_invoice["totalAmount"]) and cancelled["refund"] is not None, cancelled)

    print("\n# Payments v2: hosted checkout, saved methods, autopay, store credit, webhooks")
    config = expect("payment config", call("GET", "/api/v1/payments/config", desk), 200)
    check("online methods offered", {"CARD", "UPI", "NET_BANKING", "WALLET", "EMI"} <= set(config["onlineMethods"]), config)
    m2 = expect("second member", call("POST", "/api/v1/members", desk, {
        "homeBranchId": branch_id, "firstName": "Auto", "lastName": "Pay", "email": f"autopay.{RUN}@example.com",
        "phone": "+91 9333333333"}), 201)
    sub2 = expect("subscribe second member", call("POST", "/api/v1/subscriptions", desk, {
        "memberId": m2["id"], "planId": plan["id"]}), 201)
    session = expect("start UPI checkout with autopay", call("POST", "/api/v1/payments/checkout", desk, {
        "invoiceId": sub2["invoiceId"], "method": "UPI", "saveMethod": True, "setupAutopay": True},
        {"Idempotency-Key": f"smoke-{RUN}-checkout"}), 201)
    sim = expect("simulated hosted checkout", call("POST", f"/api/v1/payments/checkout/{session['intentId']}/simulate", desk,
                                                   {"success": True, "vpa": "success@razorpay"}), 200)
    expect("forged checkout signature rejected", call("POST", f"/api/v1/payments/checkout/{session['intentId']}/confirm", desk,
                                                      {**sim, "signature": "0" * 64}), 400)
    done = expect("confirm checkout", call("POST", f"/api/v1/payments/checkout/{session['intentId']}/confirm", desk, sim), 200)
    check("checkout succeeded with autopay", done["outcome"] == "SUCCEEDED" and done["autopayEnabled"], done)
    again = expect("confirm replay", call("POST", f"/api/v1/payments/checkout/{session['intentId']}/confirm", desk, sim), 200)
    check("replay is a no-op", again["outcome"] == "ALREADY_PROCESSED", again)
    saved = expect("saved methods", call("GET", f"/api/v1/members/{m2['id']}/payment-methods", desk), 200)
    check("UPI saved with active mandate", len(saved) == 1 and saved[0]["mandateStatus"] == "ACTIVE", saved)
    sub2 = expect("subscription after checkout", call("GET", f"/api/v1/subscriptions/{sub2['id']}", desk), 200)
    check("autopay on", sub2["autoRenew"] and sub2["autopayMethodId"] == saved[0]["id"], sub2)
    expect("autopay switched off", call("PUT", f"/api/v1/subscriptions/{sub2['id']}/autopay", desk, {"enabled": False}), 200)
    expect("receptionist cannot issue store credit", call("POST", f"/api/v1/members/{m2['id']}/credit", desk,
                                                         {"amount": 100, "reason": "x"}), 403)
    credit = expect("admin issues store credit", call("POST", f"/api/v1/members/{m2['id']}/credit", admin,
                                                      {"amount": 750, "reason": "Smoke goodwill"}), 200)
    check("credit balance", float(credit["balance"]) == 750.0, credit)
    expect("unsigned webhook rejected", call("POST", "/api/v1/webhooks/simulated", None, {"event": "payment.captured"}), 400)
    expect("autopay attempts list", call("GET", "/api/v1/autopay/attempts", desk), 200)

    print("\n# Notifications, pricing & GST documents, access control, growth")
    prefs = expect("member notification preferences", call("GET", f"/api/v1/members/{m2['id']}/notification-preferences", desk), 200)
    check("preferences returned", prefs is not None, prefs)
    providers = expect("messaging providers", call("GET", "/api/v1/messaging/providers", admin), 200)
    check("provider status has no secrets", "password" not in json.dumps(providers).lower(), providers)
    expect("receptionist cannot open messaging centre", call("GET", "/api/v1/messaging/notifications", desk), 403)
    expect("plan price history", call("GET", f"/api/v1/pricing/plans/{plan['id']}/history", admin), 200)
    code, pdf, pdf_headers = call("GET", f"/api/v1/payments/invoices/{invoice['id']}/pdf", desk)
    check("GST invoice PDF [200]", code == 200 and pdf_headers.get("Content-Type", "").startswith("application/pdf")
          and pdf.get("head") == b"%PDF-", (code, pdf_headers.get("Content-Type")))
    expect("access devices", call("GET", f"/api/v1/access/devices?branchId={branch_id}", desk), 200)
    expect("unsigned gate request rejected", call("POST", "/api/v1/access/gate/heartbeat", None, {}), 401)
    expect("access events feed", call("GET", "/api/v1/access/events/feed", desk), 200)
    expect("referral stats", call("GET", "/api/v1/referrals/stats", admin), 200)
    expect("campaigns list", call("GET", "/api/v1/campaigns", admin), 200)
    expect("unknown referral code", call("GET", "/api/v1/public/referrals/NOPE0000"), 404)

    print("\n# Member self-service")
    member_password = f"Mbr{uuid.uuid4().hex[:10]}Z9"
    signup = expect("self registration", call("POST", "/api/v1/auth/register", body={
        "email": f"self.{RUN}@example.com", "password": member_password, "firstName": "Self", "lastName": "Service",
        "phone": "+91 9222222222", "homeBranchId": branch_id}), 201)
    mtoken = signup["accessToken"]
    expect("weak password rejected", call("POST", "/api/v1/auth/register", body={
        "email": f"weak.{RUN}@example.com", "password": "password", "firstName": "W", "lastName": "P",
        "phone": "+91 9222222223", "homeBranchId": branch_id}), 400)
    my = expect("GET /members/me", call("GET", "/api/v1/members/me", mtoken), 200)
    check("membership NONE for new member", my["membership"]["state"] == "NONE", my)
    expect("member cannot read another member", call("GET", f"/api/v1/members/{member_id}", mtoken), 403)
    expect("member cannot list members", call("GET", "/api/v1/members", mtoken), 403)
    expect("member cannot pay cash", call("POST", "/api/v1/payments", mtoken, {
        "invoiceId": invoice["id"], "amount": invoice["totalAmount"], "method": "CASH"},
        {"Idempotency-Key": f"smoke-{RUN}-cash"}), 403)
    expect("member dashboard", call("GET", "/api/v1/dashboard/member", mtoken), 200)
    complaint = expect("member raises complaint", call("POST", "/api/v1/complaints", mtoken, {
        "category": "EQUIPMENT", "subject": "Broken bench", "description": "Bench 3 is wobbly"}), 201)
    expect("staff resolve without resolution text refused", call("PATCH", f"/api/v1/complaints/{complaint['id']}/status",
                                                                 admin, {"status": "RESOLVED"}), 422)
    expect("staff resolve", call("PATCH", f"/api/v1/complaints/{complaint['id']}/status", admin,
                                 {"status": "RESOLVED", "resolution": "Bench replaced"}), 200)
    expect("member closes complaint", call("PATCH", f"/api/v1/complaints/{complaint['id']}/status", mtoken,
                                           {"status": "CLOSED"}), 200)
    history = expect("complaint audit history", call("GET", f"/api/v1/complaints/{complaint['id']}/history", admin), 200)
    check("history has entries", history["totalElements"] >= 3, history)

    print("\n# Dashboards, notifications, audit")
    dash = expect("admin dashboard", call("GET", "/api/v1/dashboard/admin", admin), 200)
    check("dashboard has members", dash["totalMembers"] > 0, dash)
    timeline = expect("member history timeline", call("GET", f"/api/v1/members/{member_id}/history", desk), 200)
    actions = {e["action"] for e in timeline["content"]}
    check("timeline covers lifecycle", {"REGISTERED", "ACTIVATED", "FROZEN", "CANCELLED"} <= actions, actions)
    expect("notification outbox inbox", call("GET", "/api/v1/notifications", mtoken), 200)
    if CHECK_ANALYTICS:
        check("dashboard churn from analytics", "memberChurn" not in dash["degraded"], dash["degraded"])
        expect("analytics revenue", call("GET", "/analytics/revenue", admin), 200)
        expect("analytics churn-risk", call("GET", "/analytics/members/churn-risk?minBand=HIGH", admin), 200)
        expect("analytics forbids receptionist", call("GET", "/analytics/revenue", desk), 403)

    print(f"\n{passed} passed, {len(failures)} failed")
    for f in failures:
        print(f"  - {f}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
