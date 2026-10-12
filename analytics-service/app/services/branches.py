"""Branch comparison (pure Pandas), composed from the other services' building blocks.

Per branch:
* activeMembers      = members (by home branch) with current access today
* newMembers         = members (by home branch) registered in the range
* visits             = check-ins at the branch in the range (branch local dates)
* visitsPerActiveMember = visits / activeMembers
* netRevenue         = gross - refunds for invoices issued by the branch (see revenue service)
* revenuePerActiveMember = netRevenue / activeMembers
* peakAvgOccupancy   = highest hourly average concurrent occupancy (see attendance service);
  peakOccupancyPct = peakAvgOccupancy / capacity x 100
* openComplaints     = complaints currently OPEN / IN_PROGRESS / ESCALATED / REOPENED
* revenueRank        = 1 for the highest net revenue (ties share the best rank)
"""

from __future__ import annotations

from datetime import date
from typing import Any

import pandas as pd

from app.services.attendance import hourly_occupancy, prepare_visits
from app.services.common import ensure_columns, in_date_range, money, num, safe_div
from app.services.members import prepare_members
from app.services.revenue import net_revenue_by_branch
from app.services.subscriptions import current_access, prepare_subscriptions

BRANCH_COLUMNS = ("branch_id", "code", "name", "capacity", "timezone", "status")


def compute_branches(
    branches: pd.DataFrame,
    members: pd.DataFrame,
    subs: pd.DataFrame,
    visits: pd.DataFrame,
    payments: pd.DataFrame,
    refunds: pd.DataFrame,
    complaints: pd.DataFrame,
    date_from: date,
    date_to: date,
    today: date,
    tz: str,
) -> dict[str, Any]:
    br = ensure_columns(branches, BRANCH_COLUMNS)
    members_p = prepare_members(members, tz)
    subs_p = prepare_subscriptions(subs, tz)

    access = current_access(subs_p, today)
    active = (
        members_p[members_p["member_id"].isin(access["member_id"])]
        .groupby("home_branch_id")["member_id"]
        .nunique()
    )
    new = (
        members_p[in_date_range(members_p["registered_local"], date_from, date_to)]
        .groupby("home_branch_id")["member_id"]
        .nunique()
    )

    v = prepare_visits(visits, date_from, date_to, default_tz=tz)
    visit_counts = v.groupby("branch_id").size()

    revenue = net_revenue_by_branch(payments, refunds, date_from, date_to, tz)
    cp = ensure_columns(complaints, ("complaint_id", "branch_id", "member_id", "status", "created_at"))
    open_complaints = cp.groupby("branch_id").size()

    rows = []
    for b in br.itertuples(index=False):
        bid = int(b.branch_id)
        n_active = int(active.get(bid, 0))
        n_visits = int(visit_counts.get(bid, 0))
        net = float(revenue["net"].get(bid, 0.0)) if len(revenue) else 0.0
        hours, _ = hourly_occupancy(v[v["branch_id"] == bid], date_from, date_to)
        peak = float(hours.max()) if len(hours) else 0.0
        rows.append(
            {
                "branch_id": bid,
                "code": b.code,
                "name": b.name,
                "status": b.status,
                "capacity": None if pd.isna(b.capacity) else int(b.capacity),
                "active_members": n_active,
                "new_members": int(new.get(bid, 0)),
                "visits": n_visits,
                "visits_per_active_member": num(safe_div(n_visits, n_active)),
                "net_revenue": money(net),
                "revenue_per_active_member": money(safe_div(net, n_active)),
                "peak_avg_occupancy": num(peak),
                "peak_occupancy_pct": num(safe_div(peak * 100, b.capacity)),
                "open_complaints": int(open_complaints.get(bid, 0)),
            }
        )

    if rows:
        ranks = pd.Series([r["net_revenue"] or 0.0 for r in rows]).rank(method="min", ascending=False)
        for r, rank in zip(rows, ranks, strict=True):
            r["revenue_rank"] = int(rank)
        rows.sort(key=lambda r: (r["revenue_rank"], r["branch_id"]))

    summary = {
        "branches": len(rows),
        "active_members": sum(r["active_members"] for r in rows),
        "visits": sum(r["visits"] for r in rows),
        "net_revenue": money(sum(r["net_revenue"] or 0.0 for r in rows)),
        "open_complaints": sum(r["open_complaints"] for r in rows),
    }
    return {"branches": rows, "summary": summary}
