package com.gymops.common.exception;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hibernate.exception.ConstraintViolationException;

/**
 * Maps named database constraints to API errors. Several business invariants (no overlapping
 * subscriptions, one open visit per member, one settled payment per invoice ...) are enforced by
 * PostgreSQL itself; this registry turns those violations into meaningful 409 responses.
 */
public final class DatabaseConstraints {

    public record Mapping(ErrorCode code, String message) {
    }

    private static final Map<String, Mapping> MAPPINGS = Map.ofEntries(
            Map.entry("ex_subscriptions_no_overlap", new Mapping(ErrorCode.SUBSCRIPTION_OVERLAP,
                    "Member already has a subscription covering an overlapping period")),
            Map.entry("ux_subscriptions_one_pending", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "Member already has a subscription awaiting payment")),
            Map.entry("ux_attendance_one_open_visit", new Mapping(ErrorCode.DUPLICATE_CHECK_IN,
                    "Member is already checked in")),
            Map.entry("ux_payments_invoice_settled", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "Invoice has already been paid")),
            Map.entry("payments_idempotency_key_key", new Mapping(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key has already been used")),
            Map.entry("ux_users_email_active", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "A user with this email already exists")),
            Map.entry("ux_members_email_active", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "A member with this email already exists")),
            Map.entry("ux_branches_code_active", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "A branch with this code already exists")),
            Map.entry("membership_plans_code_key", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "A plan with this code already exists")),
            Map.entry("ux_discounts_code", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "A discount with this code already exists")),
            Map.entry("ux_member_one_active_trainer", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "Member already has an active trainer")),
            Map.entry("ux_workout_one_active_per_member", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "Member already has an active workout plan")),
            Map.entry("ex_sessions_trainer_no_overlap", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "Trainer already has a session in this time slot")),
            Map.entry("ex_sessions_member_no_overlap", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "Member already has a session in this time slot")),
            Map.entry("ux_exercises_name", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "An exercise with this name already exists")),
            Map.entry("ux_branch_facility_name", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "Facility already exists at this branch")),
            Map.entry("staff_branch_assignments_user_id_branch_id_key", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "User is already assigned to this branch")),
            Map.entry("trainers_employee_code_key", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "Employee code already in use")),
            Map.entry("trainers_user_id_key", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "User already has a trainer profile")),
            // payments v2
            Map.entry("ux_payment_method_token", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "This payment method is already saved")),
            Map.entry("ux_payment_method_one_default", new Mapping(ErrorCode.CONCURRENT_MODIFICATION,
                    "Another default payment method was set concurrently; retry")),
            Map.entry("ux_payments_provider_reference", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "This provider payment has already been recorded")),
            Map.entry("payment_intents_idempotency_key_key", new Mapping(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key has already been used for another checkout")),
            Map.entry("member_credit_ledger_balance_after_check", new Mapping(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Insufficient store credit")),
            // group classes
            Map.entry("ux_class_booking_live", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "Member already has a booking for this class")),
            Map.entry("ex_class_sessions_instructor_no_overlap", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "The instructor already teaches a class at this time")),
            Map.entry("ux_class_types_name", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "A class type with this name already exists")),
            // leads
            Map.entry("ux_leads_open_phone", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "An open lead with this phone number already exists at this branch")),
            // access control
            Map.entry("ux_access_devices_branch_name", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "A device with this name already exists at this branch")),
            Map.entry("ux_access_devices_serial", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "A device with this serial number is already registered")),
            Map.entry("ux_access_credential_live_hash", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "This card or template is already assigned to a member")),
            Map.entry("ux_access_credential_one_qr", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "Member already has an entry pass")),
            Map.entry("ux_biometric_consent_active", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "Member already has an active biometric consent")),
            Map.entry("ux_access_alarms_device_event", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "This alarm was already reported")),
            // notification channels
            Map.entry("ux_member_notification_pref", new Mapping(ErrorCode.CONCURRENT_MODIFICATION,
                    "Notification preferences were changed at the same time; please retry")),
            // pricing & GST documents
            Map.entry("ux_plan_price_changes_one_scheduled", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "This plan already has a scheduled price change; cancel it first")),
            Map.entry("ux_price_change_notices", new Mapping(ErrorCode.CONCURRENT_MODIFICATION,
                    "A price notice was sent concurrently; retry")),
            Map.entry("gym_branches_gstin_format", new Mapping(ErrorCode.VALIDATION_FAILED, "Invalid GSTIN")),
            Map.entry("gym_branches_gstin_matches_state", new Mapping(ErrorCode.VALIDATION_FAILED,
                    "The GSTIN does not belong to the branch's GST state")),
            // growth: referrals & campaigns
            Map.entry("ck_personal_discount_owner", new Mapping(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "This offer code is personal and belongs to another member")),
            Map.entry("ux_referrals_referee_member", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "This member already has a referral")),
            Map.entry("ux_referrals_referee_lead", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "This prospect already has a referral")),
            Map.entry("ck_referrals_not_self", new Mapping(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Members cannot refer themselves")),
            Map.entry("ux_referral_rewards_one_per_beneficiary", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "This referral has already been rewarded")),
            Map.entry("ux_campaign_recipients_conversion", new Mapping(ErrorCode.DUPLICATE_RESOURCE,
                    "This purchase is already attributed to a campaign")));

    private DatabaseConstraints() {
    }

    /** PostgreSQL message format: ... violates exclusion constraint "ex_sessions_trainer_no_overlap" */
    private static final Pattern MESSAGE_CONSTRAINT = Pattern.compile("constraint \"([A-Za-z0-9_]+)\"");

    public static Optional<String> constraintName(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve && cve.getConstraintName() != null) {
                return Optional.of(cve.getConstraintName());
            }
            if (t.getCause() == t) {
                break;
            }
        }
        // Hibernate's PostgreSQL name extractor only covers unique/FK/check violations (23505/23503/23514);
        // exclusion violations (23P01) carry the constraint name only in the driver message.
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t.getMessage() != null) {
                Matcher m = MESSAGE_CONSTRAINT.matcher(t.getMessage());
                if (m.find()) {
                    return Optional.of(m.group(1));
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return Optional.empty();
    }

    public static Optional<Mapping> lookup(Throwable ex) {
        return constraintName(ex).map(MAPPINGS::get);
    }

    public static boolean isViolationOf(Throwable ex, String constraint) {
        return constraintName(ex).filter(constraint::equals).isPresent();
    }
}
