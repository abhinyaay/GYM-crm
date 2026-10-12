"""Rule-based, transparent churn-risk scoring (no machine learning).

Who is scored: members currently holding an ACTIVE or GRACE subscription with access today.

Each rule below either fires or not; the score is the sum of the weights of the rules that fired,
capped at 100. Every fired rule is returned as a human-readable reason, so a manager can see
exactly why a member is flagged. ALL WEIGHTS AND THRESHOLDS LIVE IN THIS MODULE'S CONSTANTS.

=========================  ======  ==============================================================
Rule code                  Weight  Fires when
=========================  ======  ==============================================================
NO_VISIT_30_DAYS            35     >= 30 days since the last visit (supersedes NO_VISIT_14_DAYS)
NO_VISIT_14_DAYS            20     >= 14 days since the last visit
VISIT_FREQUENCY_DROP        20     visits in the last 30 days < 50 % of the average 30-day visit
                                   count over the previous 60 days, and that baseline is >= 2
EXPIRING_WITHOUT_RENEWAL    25     subscription ends within 14 days (or is already in GRACE) and no
                                   committed follow-on subscription exists
RECENT_FAILED_PAYMENT       15     a FAILED payment attempt in the last 30 days
OPEN_COMPLAINT              10     an open complaint (OPEN/IN_PROGRESS/ESCALATED/REOPENED) raised
                                   in the last 60 days
FROZEN_THIS_TERM            10     the current subscription has been frozen at least once
=========================  ======  ==============================================================

Members who never visited are measured from their current subscription start date.
Bands: HIGH >= 60, MEDIUM >= 30, otherwise LOW. Day windows include today and use the business
timezone.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta
from typing import Any

import pandas as pd

from app.services.common import ensure_columns, to_local
from app.services.members import prepare_members, visits_per_member
from app.services.subscriptions import current_access, has_successor, prepare_subscriptions


@dataclass(frozen=True)
class ChurnRule:
    code: str
    weight: int
    description: str


# ---- the single source of truth for weights ------------------------------------------------
RULES: dict[str, ChurnRule] = {
    r.code: r
    for r in (
        ChurnRule("NO_VISIT_30_DAYS", 35, "No visit in 30 or more days"),
        ChurnRule("NO_VISIT_14_DAYS", 20, "No visit in 14 or more days"),
        ChurnRule("VISIT_FREQUENCY_DROP", 20, "Visit frequency dropped by more than half"),
        ChurnRule("EXPIRING_WITHOUT_RENEWAL", 25, "Subscription ending soon with no renewal in place"),
        ChurnRule("RECENT_FAILED_PAYMENT", 15, "Failed payment in the last 30 days"),
        ChurnRule("OPEN_COMPLAINT", 10, "Open complaint raised in the last 60 days"),
        ChurnRule("FROZEN_THIS_TERM", 10, "Subscription frozen during the current term"),
    )
}
INACTIVITY_WARN_DAYS = 14
INACTIVITY_HIGH_DAYS = 30
RECENT_WINDOW_DAYS = 30
BASELINE_WINDOW_DAYS = 60
FREQUENCY_DROP_RATIO = 0.5
MIN_BASELINE_VISITS = 2.0
EXPIRY_WINDOW_DAYS = 14
FAILED_PAYMENT_LOOKBACK_DAYS = 30
COMPLAINT_LOOKBACK_DAYS = 60
MAX_SCORE = 100
BANDS: tuple[tuple[str, int], ...] = (("HIGH", 60), ("MEDIUM", 30), ("LOW", 0))
BAND_ORDER = {"LOW": 0, "MEDIUM": 1, "HIGH": 2}
# How much visit history the router must load (recent + baseline windows).
VISIT_HISTORY_DAYS = RECENT_WINDOW_DAYS + BASELINE_WINDOW_DAYS


def band_for(score: int) -> str:
    for name, minimum in BANDS:
        if score >= minimum:
            return name
    return "LOW"


def rules_catalogue() -> list[dict[str, Any]]:
    return [{"code": r.code, "weight": r.weight, "description": r.description} for r in RULES.values()]


def _reason(code: str, text: str) -> dict[str, Any]:
    return {"code": code, "points": RULES[code].weight, "description": text}


def score_members(
    members: pd.DataFrame,
    subs: pd.DataFrame,
    visits: pd.DataFrame,
    last_visits: pd.DataFrame,
    failed_payments: pd.DataFrame,
    complaints: pd.DataFrame,
    today: date,
    tz: str,
) -> pd.DataFrame:
    """Return one row per scored member, sorted by score (desc) then days since last visit (desc).

    Columns: member_id, member_code, name, branch_id, branch_name, subscription_id, subscription_status,
    plan_name, subscription_end_date, last_visit, days_since_last_visit, visits_last_30_days, score,
    band, reasons
    """
    members_p = prepare_members(members, tz).drop_duplicates("member_id").set_index("member_id")
    subs_p = prepare_subscriptions(subs, tz)
    t = pd.Timestamp(today)

    current = current_access(subs_p, today)
    current = current[current["status"].isin({"ACTIVE", "GRACE"})]
    current = current[current["member_id"].isin(members_p.index)]
    current = current.sort_values("start").drop_duplicates("member_id", keep="last")
    if current.empty:
        return pd.DataFrame(
            columns=[
                "member_id",
                "member_code",
                "name",
                "branch_id",
                "branch_name",
                "subscription_id",
                "subscription_status",
                "plan_name",
                "subscription_end_date",
                "last_visit",
                "days_since_last_visit",
                "visits_last_30_days",
                "score",
                "band",
                "reasons",
            ]
        )
    current = current.assign(successor=has_successor(subs_p, current))

    recent = visits_per_member(visits, tz, today - timedelta(days=RECENT_WINDOW_DAYS - 1), today)
    baseline = visits_per_member(
        visits,
        tz,
        today - timedelta(days=RECENT_WINDOW_DAYS + BASELINE_WINDOW_DAYS - 1),
        today - timedelta(days=RECENT_WINDOW_DAYS),
    ) / (BASELINE_WINDOW_DAYS / RECENT_WINDOW_DAYS)

    lv = ensure_columns(last_visits, ("member_id", "last_check_in"))
    lv["last_check_in"] = pd.to_datetime(lv["last_check_in"], utc=True)
    lv["last_local"] = to_local(lv["last_check_in"], tz).dt.normalize()
    last_by_member = lv.drop_duplicates("member_id").set_index("member_id")

    fp = ensure_columns(failed_payments, ("payment_id", "member_id", "processed_at"))
    fp["local"] = to_local(fp["processed_at"], tz).dt.normalize()
    fp = fp[(fp["local"] >= t - pd.Timedelta(days=FAILED_PAYMENT_LOOKBACK_DAYS - 1)) & (fp["local"] <= t)]
    last_failed = fp.groupby("member_id")["local"].max()

    cp = ensure_columns(complaints, ("complaint_id", "member_id", "status", "created_at"))
    cp["local"] = to_local(cp["created_at"], tz).dt.normalize()
    cp = cp[cp["local"] >= t - pd.Timedelta(days=COMPLAINT_LOOKBACK_DAYS - 1)]
    open_complaints = cp.groupby("member_id").size()

    rows: list[dict[str, Any]] = []
    for s in current.itertuples(index=False):
        mid = s.member_id
        reasons: list[dict[str, Any]] = []

        last_ts = last_by_member["last_check_in"].get(mid) if mid in last_by_member.index else None
        last_local = last_by_member["last_local"].get(mid) if mid in last_by_member.index else None
        if last_local is not None and pd.notna(last_local):
            idle_days = int((t - last_local).days)
            idle_text = f"No visit in {idle_days} days"
        else:
            last_ts = None
            idle_days = int((t - s.start).days)
            idle_text = f"No visits since the subscription started {idle_days} days ago"
        if idle_days >= INACTIVITY_HIGH_DAYS:
            reasons.append(_reason("NO_VISIT_30_DAYS", idle_text))
        elif idle_days >= INACTIVITY_WARN_DAYS:
            reasons.append(_reason("NO_VISIT_14_DAYS", idle_text))

        v30 = int(recent.get(mid, 0))
        base = float(baseline.get(mid, 0.0))
        if base >= MIN_BASELINE_VISITS and v30 < FREQUENCY_DROP_RATIO * base:
            reasons.append(
                _reason(
                    "VISIT_FREQUENCY_DROP",
                    f"{v30} visits in the last 30 days vs an average of {base:.1f} per 30 days "
                    "in the previous 60",
                )
            )

        days_to_end = int((s.end - t).days)
        if not s.successor and (s.status == "GRACE" or 0 <= days_to_end <= EXPIRY_WINDOW_DAYS):
            if s.status == "GRACE" or days_to_end < 0:
                text = (
                    f"Subscription ended {abs(days_to_end)} days ago (grace period) with no renewal in place"
                )
            elif days_to_end == 0:
                text = "Subscription ends today with no renewal in place"
            else:
                text = f"Subscription ends in {days_to_end} days with no renewal in place"
            reasons.append(_reason("EXPIRING_WITHOUT_RENEWAL", text))

        if mid in last_failed.index:
            reasons.append(
                _reason("RECENT_FAILED_PAYMENT", f"Failed payment on {last_failed[mid].date().isoformat()}")
            )

        n_open = int(open_complaints.get(mid, 0))
        if n_open:
            reasons.append(
                _reason(
                    "OPEN_COMPLAINT",
                    f"{n_open} open complaint{'s' if n_open > 1 else ''} raised in the last 60 days",
                )
            )

        if s.freeze_count > 0 or s.freeze_days_used > 0:
            reasons.append(
                _reason(
                    "FROZEN_THIS_TERM",
                    f"Subscription frozen {s.freeze_count} time(s), {s.freeze_days_used} days, "
                    "during the current term",
                )
            )

        score = min(MAX_SCORE, sum(r["points"] for r in reasons))
        member = members_p.loc[mid]
        rows.append(
            {
                "member_id": int(mid),
                "member_code": member["member_code"],
                "name": f"{member['first_name'] or ''} {member['last_name'] or ''}".strip(),
                "branch_id": None if pd.isna(member["home_branch_id"]) else int(member["home_branch_id"]),
                "branch_name": member["branch_name"],
                "subscription_id": int(s.subscription_id),
                "subscription_status": s.status,
                "plan_name": s.plan_name,
                "subscription_end_date": s.end.date().isoformat(),
                "last_visit": None
                if last_ts is None or pd.isna(last_ts)
                else pd.Timestamp(last_ts).isoformat(),
                "days_since_last_visit": idle_days,
                "visits_last_30_days": v30,
                "score": int(score),
                "band": band_for(score),
                "reasons": reasons,
            }
        )

    out = pd.DataFrame(rows, dtype=object)  # object dtype keeps None (not NaN) for missing values
    return out.sort_values(
        ["score", "days_since_last_visit", "member_id"], ascending=[False, False, True]
    ).reset_index(drop=True)


def paginate(scored: pd.DataFrame, min_band: str, page: int, size: int) -> dict[str, Any]:
    """Filter by minimum band and slice a page (0-based ``page``)."""
    band_counts = {
        b: int((scored["band"] == b).sum()) if len(scored) else 0 for b in ("HIGH", "MEDIUM", "LOW")
    }
    filtered = scored[scored["band"].map(BAND_ORDER) >= BAND_ORDER[min_band]] if len(scored) else scored
    total = len(filtered)
    items = filtered.iloc[page * size : (page + 1) * size]
    return {
        "items": items.to_dict(orient="records"),
        "page": page,
        "size": size,
        "total_items": total,
        "total_pages": int(-(-total // size)) if size else 0,
        "band_counts": band_counts,
        "scored_members": len(scored),
    }
