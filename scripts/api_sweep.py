#!/usr/bin/env python3
"""Broad API regression sweep against the running demo stack (stdlib only).

Calls ~240 endpoints as every role (super admin, gym admin, manager, receptionist, trainer, member)
with filters, sorting, pagination, invalid bodies and state transitions, and reports any 5xx or
unexpected status code. Requires the demo dataset (DEMO_DATA_ENABLED=true). Re-runnable.

    make sweep      # or: set -a; . ./.env; set +a; python3 scripts/api_sweep.py
"""
import json
import os
import random
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("BASE_URL", "http://localhost")
PW = os.environ["DEMO_USER_PASSWORD"]
ADMIN = (os.environ["BOOTSTRAP_ADMIN_EMAIL"], os.environ["BOOTSTRAP_ADMIN_PASSWORD"])
R = uuid.uuid4().hex[:5]
_d = random.randint(30, 300)
while time.gmtime(time.time() + _d * 86400).tm_wday == 6:   # demo trainers are not available on Sundays
    _d += 1
SLOT = time.strftime("%Y-%m-%dT05:30:00Z", time.gmtime(time.time() + _d * 86400))
SLOT2 = time.strftime("%Y-%m-%dT07:30:00Z", time.gmtime(time.time() + _d * 86400))
problems = []
count = 0


def call(method, path, token=None, body=None, headers=None):
    global count
    count += 1
    time.sleep(0.25 if path.startswith("/analytics") else 0.04)
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            try:
                return r.status, json.loads(raw) if raw else None
            except ValueError:
                return r.status, raw.decode(errors="replace")[:200]
    except urllib.error.HTTPError as e:
        if e.code == 429 and path.startswith("/api/v1/auth") and e.headers.get("Retry-After"):
            wait = int(e.headers["Retry-After"]) + 1
            print(f"   (auth rate limit hit, Retry-After {wait}s - waiting like a real client)")
            time.sleep(wait)
            return call(method, path, token, body, headers)
        raw = e.read()
        try:
            return e.code, json.loads(raw) if raw else None
        except Exception:
            return e.code, raw.decode()[:200]


def ex(label, resp, *expected):
    code, body = resp
    if code >= 500 or (expected and code not in expected):
        problems.append(f"{label}: got {code} expected {expected} :: {json.dumps(body)[:300]}")
        print("  !!", label, code, json.dumps(body)[:200])
    return body


def login(email, pw=PW):
    c, b = call("POST", "/api/v1/auth/login", body={"email": email, "password": pw})
    assert c == 200, (email, c, b)
    return b["accessToken"], b


admin, _ = login(*ADMIN)
gym, _ = login("admin@gymops.dev")
mgr, mgr_info = login("manager.blr@gymops.dev")
desk, desk_info = login("frontdesk.blr@gymops.dev")
mumdesk, _ = login("frontdesk.mum@gymops.dev")
coach, coach_info = login("trainer1.blr@gymops.dev")
mem, mem_info = login("member1.blr@gymops.dev")
blr = mgr_info["user"]["branchIds"][0]
mum = [b for b in ex("branches", call("GET", "/api/v1/branches", admin), 200)["content"] if b["code"] == "MUM-AND"][0]["id"]
print("branches blr", blr, "mum", mum)

print("# users")
ex("users search q", call("GET", "/api/v1/users?q=manager&size=5", admin), 200)
ex("users search role", call("GET", "/api/v1/users?role=TRAINER", admin), 200)
ex("users search status", call("GET", "/api/v1/users?status=ACTIVE&sort=email,desc", admin), 200)
ex("users search all filters", call("GET", "/api/v1/users?role=MANAGER&status=ACTIVE&q=blr", gym), 200)
ex("users me", call("GET", "/api/v1/users/me", coach), 200)
ex("users update me", call("PUT", "/api/v1/users/me", coach, {"fullName": "Coach Renamed", "phone": "+91 9123456780"}), 200)
ex("users get", call("GET", f"/api/v1/users/{mgr_info['user']['id']}", gym), 200)
ex("manager cannot list users", call("GET", "/api/v1/users", mgr), 403)

print("# branches")
b = ex("branch get", call("GET", f"/api/v1/branches/{blr}", mgr), 200)
body = {k: b[k] for k in ("code", "name", "phone", "email", "addressLine1", "addressLine2", "city", "state", "postalCode", "country", "timezone", "capacity")}
ex("branch update", call("PUT", f"/api/v1/branches/{blr}", gym, body), 200)
ex("branch update bad tz", call("PUT", f"/api/v1/branches/{blr}", gym, {**body, "timezone": "Mars/Base"}), 400)
ex("branch staff", call("GET", f"/api/v1/branches/{blr}/staff", mgr), 200)
ex("branch public", call("GET", "/api/v1/branches/public"), 200)
ex("manager other branch", call("GET", f"/api/v1/branches/{mum}", mgr), 403)
fac = ex("add facility", call("POST", f"/api/v1/branches/{blr}/facilities", mgr, {"name": f"Sauna {R}", "description": "Dry sauna"}), 201)
fid = [f for f in fac["facilities"] if f["name"] == f"Sauna {R}"][0]["id"]
ex("dup facility", call("POST", f"/api/v1/branches/{blr}/facilities", mgr, {"name": f"sauna {R}"}), 409)
ex("update facility", call("PUT", f"/api/v1/branches/{blr}/facilities/{fid}", mgr, {"name": f"Sauna {R}", "available": False}), 200)
ex("delete facility", call("DELETE", f"/api/v1/branches/{blr}/facilities/{fid}", mgr), 204)
hours = [{"dayOfWeek": d, "openTime": "05:30", "closeTime": "22:30", "closed": False} for d in range(1, 7)] + [{"dayOfWeek": 7, "closed": True}]
ex("set hours", call("PUT", f"/api/v1/branches/{blr}/operating-hours", mgr, {"days": hours}), 200)
ex("bad hours", call("PUT", f"/api/v1/branches/{blr}/operating-hours", mgr, {"days": [{"dayOfWeek": 1, "openTime": "22:00", "closeTime": "06:00"}]}), 400)
ex("dup days", call("PUT", f"/api/v1/branches/{blr}/operating-hours", mgr, {"days": [{"dayOfWeek": 1, "closed": True}, {"dayOfWeek": 1, "closed": True}]}), 400)
ex("delete branch with members", call("DELETE", f"/api/v1/branches/{blr}", admin), 422)
ex("branches sort", call("GET", "/api/v1/branches?sort=city,desc", admin), 200)

print("# plans")
plans = ex("plans", call("GET", "/api/v1/plans"), 200)
plans = [p for p in plans if float(p["price"]) > 0] + [p for p in plans if float(p["price"]) == 0]  # paid plans first
ex("plan get public", call("GET", f"/api/v1/plans/{plans[0]['id']}"), 200)
ex("plans admin", call("GET", "/api/v1/plans/admin?status=ACTIVE&sort=price,desc", mgr), 200)
ex("plans admin anon", call("GET", "/api/v1/plans/admin"), 401)
newplan = ex("plan create draft", call("POST", "/api/v1/plans", gym, {"code": f"DRAFT{R}", "name": "Draft", "billingPeriod": "QUARTERLY", "price": "5000", "cancellationPolicy": "PRORATED", "benefits": ["A", "B"]}), 201)
ex("draft hidden from public", call("GET", f"/api/v1/plans/{newplan['id']}"), 404)
ex("draft visible to staff", call("GET", f"/api/v1/plans/{newplan['id']}", desk), 200)
ex("plan custom missing days", call("POST", "/api/v1/plans", gym, {"code": f"C{R}", "name": "C", "billingPeriod": "CUSTOM", "price": "1", "cancellationPolicy": "PRORATED"}), 400)
ex("plan update", call("PUT", f"/api/v1/plans/{newplan['id']}", gym, {"code": f"DRAFT{R}", "name": "Draft 2", "billingPeriod": "QUARTERLY", "price": "5500", "cancellationPolicy": "PRORATED", "benefits": ["C"]}), 200)
ex("plan activate", call("PATCH", f"/api/v1/plans/{newplan['id']}/status", gym, {"status": "ACTIVE"}), 200)
after = ex("plans after activate", call("GET", "/api/v1/plans"), 200)
if not any(p["id"] == newplan["id"] for p in after):
    problems.append("activated plan missing from cached public catalogue (cache not evicted)")
ex("plan public after activate", call("GET", f"/api/v1/plans/{newplan['id']}"), 200)
ex("plan archive", call("PATCH", f"/api/v1/plans/{newplan['id']}/status", gym, {"status": "ARCHIVED"}), 200)
after = ex("plans after archive", call("GET", "/api/v1/plans"), 200)
if any(p["id"] == newplan["id"] for p in after):
    problems.append("archived plan still in public catalogue (cache not evicted)")
ex("plan back to draft", call("PATCH", f"/api/v1/plans/{newplan['id']}/status", gym, {"status": "DRAFT"}), 409)
ex("reactivate archived", call("PATCH", f"/api/v1/plans/{newplan['id']}/status", gym, {"status": "ACTIVE"}), 409)

print("# discounts")
ex("discounts list", call("GET", "/api/v1/payments/discounts", mgr), 200)
d = ex("discount create", call("POST", "/api/v1/payments/discounts", gym, {"code": f"TEN{R}", "discountType": "PERCENTAGE", "value": 10, "validFrom": "2026-01-01", "maxRedemptions": 1}), 201)
ex("discount >100%", call("POST", "/api/v1/payments/discounts", gym, {"code": f"BAD{R}", "discountType": "PERCENTAGE", "value": 150, "validFrom": "2026-01-01"}), 400)
ex("discount dup", call("POST", "/api/v1/payments/discounts", gym, {"code": f"ten{R}", "discountType": "FIXED_AMOUNT", "value": 10, "validFrom": "2026-01-01"}), 409)

print("# members")
ex("members search q", call("GET", "/api/v1/members?q=sharma", desk), 200)
ex("members search code", call("GET", "/api/v1/members?q=M001000", mgr), 200)
for st in ("ACTIVE", "GRACE", "FROZEN", "SCHEDULED", "PENDING_PAYMENT", "NONE"):
    ex(f"members membership={st}", call("GET", f"/api/v1/members?membership={st}&size=5", mgr), 200)
ex("members registered range", call("GET", "/api/v1/members?registeredFrom=2026-01-01&registeredTo=2026-12-31&sort=lastName,asc", mgr), 200)
ex("members status filter", call("GET", "/api/v1/members?status=ACTIVE&branchId=" + str(blr), desk), 200)
ex("members search wildcard", call("GET", "/api/v1/members?q=%25_", desk), 200)
ex("members bad enum", call("GET", "/api/v1/members?membership=BOGUS", desk), 400)
ex("members other branch filter", call("GET", f"/api/v1/members?branchId={mum}", desk), 403)
me = ex("member me", call("GET", "/api/v1/members/me", mem), 200)
mid = me["id"]
if me.get("notes") is not None:
    problems.append("member sees staff notes")
ex("member by code", call("GET", f"/api/v1/members/code/{me['memberCode'].lower()}", desk), 200)
ex("member history", call("GET", f"/api/v1/members/{mid}/history", mem), 200)
upd = {"homeBranchId": me["homeBranchId"], "firstName": me["firstName"], "lastName": me["lastName"], "email": me["email"], "phone": "+91 9876543210",
       "emergencyContacts": [{"name": "Mom", "relationship": "PARENT", "phone": "+91 9000011111", "primary": True}]}
ex("member self update", call("PUT", f"/api/v1/members/{mid}", mem, upd), 200)
again = ex("member get after update", call("GET", f"/api/v1/members/{mid}", mem), 200)
if again and again["phone"] != "+91 9876543210":
    problems.append("stale cached member profile after update")
ex("member self change branch", call("PUT", f"/api/v1/members/{mid}", mem, {**upd, "homeBranchId": mum}), 403)
ex("two primary contacts", call("PUT", f"/api/v1/members/{mid}", desk, {**upd, "emergencyContacts": [
    {"name": "A", "relationship": "X", "phone": "+91 9000011111", "primary": True}, {"name": "B", "relationship": "Y", "phone": "+91 9000011112", "primary": True}]}), 422)
ex("trainer cannot update member", call("PUT", f"/api/v1/members/{mid}", coach, upd), 403)
newm = ex("register member", call("POST", "/api/v1/members", desk, {"homeBranchId": blr, "firstName": "Exp", "lastName": "Lore", "email": f"exp{R}@example.com", "phone": "+91 9000000099", "dateOfBirth": "1990-05-05", "gender": "FEMALE", "notes": "VIP"}), 201)
nid = newm["id"]
ex("member status suspend", call("PATCH", f"/api/v1/members/{nid}/status", mgr, {"status": "SUSPENDED", "reason": "test"}), 200)
ex("suspended cannot subscribe", call("POST", "/api/v1/subscriptions", desk, {"memberId": nid, "planId": plans[0]["id"]}), 422)
ex("member reactivate", call("PATCH", f"/api/v1/members/{nid}/status", mgr, {"status": "ACTIVE"}), 200)
ex("future dob", call("POST", "/api/v1/members", desk, {"homeBranchId": blr, "firstName": "F", "lastName": "D", "email": f"fd{R}@example.com", "phone": "+91 9000000098", "dateOfBirth": "2999-01-01"}), 400)
ex("delete member no membership", call("DELETE", f"/api/v1/members/{nid}", mgr), 204)
ex("get deleted member", call("GET", f"/api/v1/members/{nid}", mgr), 404)
ex("re-register same email after delete", call("POST", "/api/v1/members", desk, {"homeBranchId": blr, "firstName": "Exp", "lastName": "Again", "email": f"exp{R}@example.com", "phone": "+91 9000000099"}), 201)

print("# subscriptions")
ex("subs search", call("GET", f"/api/v1/subscriptions?status=ACTIVE&endingTo=2026-12-31&sort=endDate,asc", mgr), 200)
ex("subs search plan", call("GET", f"/api/v1/subscriptions?planId={plans[0]['id']}&branchId={blr}", desk), 200)
ex("subs member history", call("GET", f"/api/v1/subscriptions/members/{mid}", mem), 200)
ex("subs search as member", call("GET", "/api/v1/subscriptions", mem), 403)
ex("subscribe past date", call("POST", "/api/v1/subscriptions", desk, {"memberId": mid, "planId": plans[0]["id"], "startDate": "2020-01-01"}), 400)
ex("subscribe bad discount", call("POST", "/api/v1/subscriptions", desk, {"memberId": mid, "planId": plans[0]["id"], "startDate": time.strftime("%Y-%m-%d", time.localtime(time.time() + 80 * 86400)), "discountCode": "NOPE"}), 422, 409)
ex("subscribe draft plan", call("POST", "/api/v1/subscriptions", desk, {"memberId": mid, "planId": newplan["id"]}), 422)
ex("subscribe unknown member", call("POST", "/api/v1/subscriptions", desk, {"memberId": 999999, "planId": plans[0]["id"]}), 404)
ex("subscribe other branch member", call("POST", "/api/v1/subscriptions", mumdesk, {"memberId": mid, "planId": plans[0]["id"]}), 403)
# fresh member: future start + limited discount + member self payment
fm = ex("register for subs", call("POST", "/api/v1/members", desk, {"homeBranchId": blr, "firstName": "Sub", "lastName": "Flow", "email": f"sub{R}@example.com", "phone": "+91 9000000097"}), 201)
s = ex("future subscription with discount", call("POST", "/api/v1/subscriptions", desk, {"memberId": fm["id"], "planId": plans[0]["id"], "startDate": time.strftime("%Y-%m-%d", time.localtime(time.time() + 5 * 86400)), "discountCode": f"TEN{R}"}), 201)
inv = ex("invoice", call("GET", f"/api/v1/payments/invoices/{s['invoiceId']}", desk), 200)
ex("invoices outstanding", call("GET", f"/api/v1/payments/invoices/outstanding?branchId={blr}", desk), 200)
ex("invoices overdue", call("GET", "/api/v1/payments/invoices?overdue=true", mgr), 200)
ex("invoices date filter", call("GET", "/api/v1/payments/invoices?from=2026-01-01&to=2026-12-31&status=PAID&sort=totalAmount,desc", mgr), 200)
ex("invoices as member", call("GET", "/api/v1/payments/invoices", mem), 200)
second = ex("register for limit", call("POST", "/api/v1/members", desk, {"homeBranchId": blr, "firstName": "Lim", "lastName": "It", "email": f"lim{R}@example.com", "phone": "+91 9000000096"}), 201)
ex("discount limit reached", call("POST", "/api/v1/subscriptions", desk, {"memberId": second["id"], "planId": plans[0]["id"], "discountCode": f"TEN{R}"}), 422)
p = ex("pay future sub", call("POST", "/api/v1/payments", desk, {"invoiceId": inv["id"], "amount": inv["totalAmount"], "method": "CARD", "paymentToken": "tok_visa"}, {"Idempotency-Key": f"exp-{R}-1"}), 201)
ex("future sub scheduled", call("GET", f"/api/v1/subscriptions/{s['id']}", desk), 200)
ex("freeze scheduled", call("POST", f"/api/v1/subscriptions/{s['id']}/freeze", desk, {"days": 7}), 409)
ex("checkin scheduled member", call("POST", "/api/v1/attendance/check-in", desk, {"memberId": fm["id"], "branchId": blr}), 422)
ex("payment get", call("GET", f"/api/v1/payments/{p['id']}", desk), 200)
ex("payments search", call("GET", f"/api/v1/payments?status=SUCCEEDED&method=CARD&from=2026-01-01&sort=amount,desc", mgr), 200)
ex("payments as member", call("GET", "/api/v1/payments", mem), 200)
ex("refund too much", call("POST", f"/api/v1/payments/{p['id']}/refunds", mgr, {"amount": 999999, "reason": "x"}), 422)
r1 = ex("partial refund", call("POST", f"/api/v1/payments/{p['id']}/refunds", mgr, {"amount": 100, "reason": "goodwill"}, {"Idempotency-Key": f"rf-{R}"}), 201)
r2 = ex("partial refund replay", call("POST", f"/api/v1/payments/{p['id']}/refunds", mgr, {"amount": 100, "reason": "goodwill"}, {"Idempotency-Key": f"rf-{R}"}), 201)
if r1 and r2 and r1["id"] != r2["id"]:
    problems.append("refund idempotency replay created a second refund")
ex("refunds list", call("GET", f"/api/v1/payments/{p['id']}/refunds", desk), 200)
ex("desk cannot refund", call("POST", f"/api/v1/payments/{p['id']}/refunds", desk, {"amount": 1, "reason": "x"}), 403)
can = ex("cancel scheduled (full prorated)", call("POST", f"/api/v1/subscriptions/{s['id']}/cancel", desk, {"reason": "changed mind"}), 200)
print("   cancel scheduled refund:", can and can["refundAmount"], "paid", inv["totalAmount"])
ex("discount deactivate", call("PATCH", f"/api/v1/payments/discounts/{d['id']}/deactivate", gym), 200)

# member self-service online purchase
ex("member subscribes self", call("POST", "/api/v1/subscriptions", mem, {"memberId": mid, "planId": plans[0]["id"], "startDate": time.strftime("%Y-%m-%d", time.localtime(time.time() + 85 * 86400))}), 201, 409)
ex("member subscribes other", call("POST", "/api/v1/subscriptions", mem, {"memberId": fm["id"], "planId": plans[0]["id"]}), 403)

print("# attendance")
ex("branch visits", call("GET", f"/api/v1/attendance/branches/{blr}?date=2026-10-01", desk), 200)
ex("daily summary", call("GET", f"/api/v1/attendance/branches/{blr}/daily-summary?date=2026-10-01", desk), 200)
ex("occupancy", call("GET", f"/api/v1/attendance/branches/{blr}/occupancy", coach), 200)
ex("member attendance history", call("GET", f"/api/v1/attendance/members/{mid}?from=2026-01-01", mem), 200)
ex("checkin by unknown code", call("POST", "/api/v1/attendance/check-in", desk, {"memberCode": "M999999", "branchId": blr}), 404)
ex("checkin both id and code", call("POST", "/api/v1/attendance/check-in", desk, {"memberId": mid, "memberCode": "X", "branchId": blr}), 400)
ex("member front desk checkin", call("POST", "/api/v1/attendance/check-in", mem, {"memberId": mid, "branchId": blr, "method": "FRONT_DESK"}), 403)
ex("checkin other branch staff", call("POST", "/api/v1/attendance/check-in", mumdesk, {"memberId": mid, "branchId": blr}), 403)

print("# trainers & workouts")
tl = ex("trainers search", call("GET", "/api/v1/trainers?specialization=strength", mem), 200)
ex("trainers search branch", call("GET", f"/api/v1/trainers?branchId={blr}&status=ACTIVE", mgr), 200)
tid = coach_info["user"]["trainerId"]
ex("trainer get", call("GET", f"/api/v1/trainers/{tid}", mem), 200)
ex("trainer clients", call("GET", f"/api/v1/trainers/{tid}/clients", coach), 200)
ex("trainer schedule", call("GET", f"/api/v1/trainers/{tid}/schedule?from=2026-09-01&to=2026-10-30", coach), 200)
ex("trainer schedule too long", call("GET", f"/api/v1/trainers/{tid}/schedule?from=2026-01-01&to=2026-12-30", coach), 400)
ex("trainer perf", call("GET", f"/api/v1/trainers/{tid}/performance", mgr), 200)
tr = ex("trainer self", call("GET", f"/api/v1/trainers/{tid}", coach), 200)
ex("trainer self update", call("PUT", f"/api/v1/trainers/{tid}", coach, {"homeBranchId": tr["homeBranchId"], "bio": "Updated bio", "yearsOfExperience": 6, "hourlyRate": tr["hourlyRate"], "specializations": ["STRENGTH"]}), 200)
ex("trainer self raise rate", call("PUT", f"/api/v1/trainers/{tid}", coach, {"homeBranchId": tr["homeBranchId"], "yearsOfExperience": 6, "hourlyRate": 99999}), 403)
ex("exercises", call("GET", "/api/v1/workouts/exercises?muscleGroup=legs&q=squat", mem), 200)
ex("exercise create", call("POST", "/api/v1/workouts/exercises", coach, {"name": f"Sled Push {R}", "muscleGroup": "LEGS"}), 201)
ex("exercise dup", call("POST", "/api/v1/workouts/exercises", coach, {"name": f"sled push {R}", "muscleGroup": "LEGS"}), 409)
ex("templates", call("GET", "/api/v1/workouts/templates", coach), 200)
clients = ex("trainer clients 2", call("GET", f"/api/v1/trainers/{tid}/clients", coach), 200)
if clients:
    cid = clients[0]["memberId"]
    ap = ex("client active plan", call("GET", f"/api/v1/workouts/members/{cid}/active-plan", coach), 200, 404)
    ex("client plans", call("GET", f"/api/v1/workouts/members/{cid}/plans", coach), 200)
    ex("client progress", call("GET", f"/api/v1/workouts/members/{cid}/progress", coach), 200)
    if isinstance(ap, dict) and "id" in ap:
        ex("update member plan", call("PUT", f"/api/v1/workouts/plans/{ap['id']}", coach, {"name": ap["name"], "goal": ap["goal"], "difficulty": "INTERMEDIATE", "durationWeeks": 10, "trainerNotes": "Progressive overload",
            "exercises": [{"exerciseId": e["exerciseId"], "dayNumber": e["dayNumber"], "sets": e["sets"] + 1, "reps": e["reps"], "durationSeconds": e["durationSeconds"]} for e in ap["exercises"]]}), 200)
        ex("plan get", call("GET", f"/api/v1/workouts/plans/{ap['id']}", coach), 200)
        ex("plan get by other member", call("GET", f"/api/v1/workouts/plans/{ap['id']}", mem), 200 if cid == mid else 403)
    sess = ex("book session", call("POST", f"/api/v1/trainers/{tid}/sessions", coach, {"memberId": cid, "startsAt": SLOT, "durationMinutes": 60}), 201)
    if isinstance(sess, dict) and "id" in sess:
        ex("cancel session", call("PATCH", f"/api/v1/trainers/{tid}/sessions/{sess['id']}", coach, {"status": "CANCELLED", "notes": "sick"}), 200)
        ex("rebook same slot after cancel", call("POST", f"/api/v1/trainers/{tid}/sessions", coach, {"memberId": cid, "startsAt": SLOT, "durationMinutes": 60}), 201)
ex("non-client session", call("POST", f"/api/v1/trainers/{tid}/sessions", coach, {"memberId": fm["id"], "startsAt": SLOT2, "durationMinutes": 60}), 422)
ex("member reads own active plan", call("GET", f"/api/v1/workouts/members/{mid}/active-plan", mem), 200, 404)
ex("member records progress", call("POST", f"/api/v1/workouts/members/{mid}/progress", mem, {"weightKg": 70.5}), 201)
ex("member progress other", call("GET", f"/api/v1/workouts/members/{fm['id']}/progress", mem), 403)

print("# complaints")
c = ex("complaint member", call("POST", "/api/v1/complaints", mem, {"category": "FACILITY", "subject": "AC broken", "description": "Too hot"}), 201)
ex("complaints search member", call("GET", "/api/v1/complaints", mem), 200)
ex("complaints search staff", call("GET", f"/api/v1/complaints?status=OPEN&priority=MEDIUM&category=FACILITY&slaBreached=false&sort=createdAt,desc", mgr), 200)
ex("complaints sla breached", call("GET", "/api/v1/complaints?slaBreached=true", mgr), 200)
ex("complaint comment member", call("POST", f"/api/v1/complaints/{c['id']}/comments", mem, {"body": "Still hot", "internal": True}), 201)
ex("complaint comment staff internal", call("POST", f"/api/v1/complaints/{c['id']}/comments", desk, {"body": "Technician called", "internal": True}), 201)
cm = ex("member comments", call("GET", f"/api/v1/complaints/{c['id']}/comments", mem), 200)
if cm and any(x["internal"] for x in cm):
    problems.append("member can see internal complaint comments")
ex("member cannot escalate", call("POST", f"/api/v1/complaints/{c['id']}/escalate", mem, {"reason": "x"}), 403)
ex("member cannot set in progress", call("PATCH", f"/api/v1/complaints/{c['id']}/status", mem, {"status": "IN_PROGRESS"}), 403)
ex("assign to staff of other branch", call("PATCH", f"/api/v1/complaints/{c['id']}/assignee", mgr, {"assigneeUserId": 999999}), 404)
ex("complaint history member", call("GET", f"/api/v1/complaints/{c['id']}/history", mem), 403)
ex("other staff cannot see", call("GET", f"/api/v1/complaints/{c['id']}", mumdesk), 403)

print("# notifications")
inbox = ex("inbox", call("GET", "/api/v1/notifications?unreadOnly=true", mem), 200)
ex("unread count", call("GET", "/api/v1/notifications/unread-count", mem), 200)
if inbox and inbox["content"]:
    ex("mark read", call("PATCH", f"/api/v1/notifications/{inbox['content'][0]['id']}/read", mem), 200)
    ex("mark read someone else's", call("PATCH", f"/api/v1/notifications/{inbox['content'][0]['id']}/read", coach), 404)
ex("read all", call("POST", "/api/v1/notifications/read-all", mem), 200)
ex("outbox admin", call("GET", "/api/v1/notifications/admin?status=SENT&size=5", admin), 200)
ex("outbox admin forbidden", call("GET", "/api/v1/notifications/admin?status=SENT", gym), 403)

print("# dashboards")
ex("admin dash branch", call("GET", f"/api/v1/dashboard/admin?branchId={blr}", mgr), 200)
ex("admin dash all as manager", call("GET", "/api/v1/dashboard/admin", mgr), 200)
ex("admin dash other branch", call("GET", f"/api/v1/dashboard/admin?branchId={mum}", mgr), 403)
ex("trainer dash", call("GET", "/api/v1/dashboard/trainer", coach), 200)
ex("trainer dash mgr", call("GET", f"/api/v1/dashboard/trainer?trainerId={tid}", mgr), 200)
ex("member dash", call("GET", "/api/v1/dashboard/member", mem), 200)
ex("member dash staff", call("GET", f"/api/v1/dashboard/member?memberId={mid}", desk), 200)
ex("member dash other member", call("GET", f"/api/v1/dashboard/member?memberId={fm['id']}", mem), 403)
ex("admin dash as gym admin", call("GET", "/api/v1/dashboard/admin", gym), 200)

print("# analytics")
for path in ["/analytics/attendance?granularity=week", "/analytics/attendance?granularity=month&from=2026-01-01&to=2026-10-01",
             f"/analytics/attendance/peak-hours?branchId={blr}", "/analytics/attendance/peak-hours",
             "/analytics/revenue?granularity=month", f"/analytics/revenue?branchId={blr}&granularity=day",
             "/analytics/members?granularity=month&cohortMonths=6", "/analytics/members/churn-risk?minBand=MEDIUM&page=1&size=10",
             "/analytics/memberships", f"/analytics/memberships?branchId={mum}", "/analytics/memberships/churn?month=2026-08",
             "/analytics/trainers", "/analytics/branches"]:
    ex("analytics " + path, call("GET", path, gym), 200)
ex("analytics manager own branch", call("GET", f"/analytics/revenue?branchId={blr}", mgr), 200)
ex("analytics manager other branch", call("GET", f"/analytics/revenue?branchId={mum}", mgr), 403)
ex("analytics trainer", call("GET", "/analytics/trainers", coach), 403)
ex("analytics bad range", call("GET", "/analytics/revenue?from=2026-10-01&to=2026-01-01", gym), 400)
ex("analytics bad granularity", call("GET", "/analytics/revenue?granularity=hour", gym), 400)
ex("analytics future month", call("GET", "/analytics/memberships/churn?month=2030-01", gym), 400)

print("# auth extras")
email = f"self{R}@example.com"
reg = ex("register", call("POST", "/api/v1/auth/register", body={"email": email, "password": "SelfPassw0rd1", "firstName": "S", "lastName": "R", "phone": "+91 9000000095", "homeBranchId": blr}), 201)
ex("register dup", call("POST", "/api/v1/auth/register", body={"email": email.upper(), "password": "SelfPassw0rd1", "firstName": "S", "lastName": "R", "phone": "+91 9000000095", "homeBranchId": blr}), 409)
ex("register inactive branch?", call("POST", "/api/v1/auth/register", body={"email": f"x{R}@example.com", "password": "SelfPassw0rd1", "firstName": "S", "lastName": "R", "phone": "+91 9000000095", "homeBranchId": 999999}), 404)
ex("change pw wrong", call("POST", "/api/v1/auth/change-password", reg["accessToken"], {"currentPassword": "nope", "newPassword": "NewPassw0rd22"}), 401)
ex("change pw", call("POST", "/api/v1/auth/change-password", reg["accessToken"], {"currentPassword": "SelfPassw0rd1", "newPassword": "NewPassw0rd22"}), 204)
ex("old refresh revoked after pw change", call("POST", "/api/v1/auth/refresh", body={"refreshToken": reg["refreshToken"]}), 401)
ex("login new pw", call("POST", "/api/v1/auth/login", body={"email": email, "password": "NewPassw0rd22"}), 200)
lg = call("POST", "/api/v1/auth/login", body={"email": email, "password": "NewPassw0rd22"})[1]
ex("logout", call("POST", "/api/v1/auth/logout", body={"refreshToken": lg["refreshToken"]}), 204)
ex("refresh after logout", call("POST", "/api/v1/auth/refresh", body={"refreshToken": lg["refreshToken"]}), 401)
ex("logout unknown token", call("POST", "/api/v1/auth/logout", body={"refreshToken": "nonsense"}), 204)
ex("logout-all", call("POST", "/api/v1/auth/logout-all", reg["accessToken"]), 204)
staff = ex("create staff", call("POST", "/api/v1/users", gym, {"email": f"st{R}@example.com", "initialPassword": "StaffPassw0rd1", "fullName": "Temp", "roles": ["RECEPTIONIST"], "branchIds": [blr]}), 201)
st_login = call("POST", "/api/v1/auth/login", body={"email": f"st{R}@example.com", "password": "StaffPassw0rd1"})[1]
ex("disable staff", call("PATCH", f"/api/v1/users/{staff['id']}/status", gym, {"status": "DISABLED"}), 200)
ex("disabled login", call("POST", "/api/v1/auth/login", body={"email": f"st{R}@example.com", "password": "StaffPassw0rd1"}), 403)
ex("disabled refresh", call("POST", "/api/v1/auth/refresh", body={"refreshToken": st_login["refreshToken"]}), 401, 403)
ex("gym admin cannot disable admin", call("PATCH", f"/api/v1/users/{mgr_info['user']['id']}/status", gym, {"status": "ACTIVE"}), 200)
ex("change roles", call("PUT", f"/api/v1/users/{staff['id']}/roles", gym, {"roles": ["MANAGER"]}), 200)
ex("change roles add member", call("PUT", f"/api/v1/users/{staff['id']}/roles", gym, {"roles": ["MEMBER"]}), 422)
ex("delete user", call("DELETE", f"/api/v1/users/{staff['id']}", admin), 204)
ex("unassign staff", call("DELETE", f"/api/v1/branches/{blr}/staff/{staff['id']}", gym), 204, 404)
ex("cors preflight", call("OPTIONS", "/api/v1/plans", headers={"Origin": "http://localhost:3000", "Access-Control-Request-Method": "GET"}), 200)
ex("unknown endpoint", call("GET", "/api/v1/nope", admin), 404)
ex("wrong method", call("DELETE", "/api/v1/plans", admin), 405)
ex("lifecycle run", call("POST", "/api/v1/subscriptions/lifecycle/run", admin), 200)

print("# extra probes")
ex("openapi docs", call("GET", "/v3/api-docs"), 200)
ex("swagger ui", call("GET", "/swagger-ui/index.html"), 200)
ex("health", call("GET", "/actuator/health"), 200)
# member pays own pending invoice online
pend = ex("member own invoices", call("GET", "/api/v1/payments/invoices?status=ISSUED", mem), 200)
if pend and pend["content"]:
    iv = pend["content"][0]
    ex("member pays online", call("POST", "/api/v1/payments", mem, {"invoiceId": iv["id"], "amount": iv["totalAmount"], "method": "UPI", "paymentToken": "tok_upi"}, {"Idempotency-Key": f"memb-{R}"}), 201)
# plan change edge cases on a fresh active member
pm = ex("register pm", call("POST", "/api/v1/members", desk, {"homeBranchId": blr, "firstName": "Plan", "lastName": "Change", "email": f"pc{R}@example.com", "phone": "+91 9000000091"}), 201)
monthly = [x for x in plans if x["code"] == "MONTHLY-STD"][0]
annual = [x for x in plans if x["code"] == "ANNUAL-PREMIUM"][0]
offpeak = [x for x in plans if x["code"] == "OFFPEAK-MONTHLY"][0]
sb = ex("pm subscribe", call("POST", "/api/v1/subscriptions", desk, {"memberId": pm["id"], "planId": annual["id"]}), 201)
ivb = ex("pm invoice", call("GET", f"/api/v1/payments/invoices/{sb['invoiceId']}", desk), 200)
ex("pm pay", call("POST", "/api/v1/payments", desk, {"invoiceId": ivb["id"], "amount": ivb["totalAmount"], "method": "CASH"}, {"Idempotency-Key": f"pm-{R}"}), 201)
ex("change to same plan", call("POST", f"/api/v1/subscriptions/{sb['id']}/change-plan", desk, {"newPlanId": annual["id"]}), 422)
dg = ex("downgrade to off-peak", call("POST", f"/api/v1/subscriptions/{sb['id']}/change-plan", desk, {"newPlanId": offpeak["id"]}), 201)
if dg and dg["changeType"] != "DOWNGRADE":
    problems.append("annual->offpeak not classified as downgrade")
ex("resume not frozen", call("POST", f"/api/v1/subscriptions/{sb['id']}/resume", desk), 409)
ex("freeze over allowance", call("POST", f"/api/v1/subscriptions/{sb['id']}/freeze", desk, {"days": 200}), 422)
ex("freeze with queued downgrade", call("POST", f"/api/v1/subscriptions/{sb['id']}/freeze", desk, {"days": 10}), 200)
ex("downgrade shifted", call("GET", f"/api/v1/subscriptions/{dg['id']}", desk), 200)
ex("pay voided invoice", call("POST", f"/api/v1/subscriptions/{dg['id']}/cancel", desk, {"reason": "nah"}), 200)
ivd = ex("voided invoice", call("GET", f"/api/v1/payments/invoices/{dg['invoiceId']}", desk), 200)
ex("pay voided", call("POST", "/api/v1/payments", desk, {"invoiceId": ivd["id"], "amount": ivd["totalAmount"], "method": "CASH"}, {"Idempotency-Key": f"pv-{R}"}), 409)
ex("idempotency key reused on other invoice", call("POST", "/api/v1/payments", desk, {"invoiceId": ivd["id"], "amount": ivd["totalAmount"], "method": "CASH"}, {"Idempotency-Key": f"pm-{R}"}), 422)
ex("checkin annual member", call("POST", "/api/v1/attendance/check-in", desk, {"memberCode": pm["memberCode"], "branchId": blr}), 422, 201)

print(f"\n{count} calls, {len(problems)} problems")
for p_ in problems:
    print(" -", p_)
sys.exit(1 if problems else 0)
