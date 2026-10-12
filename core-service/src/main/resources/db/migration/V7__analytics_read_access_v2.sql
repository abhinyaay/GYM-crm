-- Explicit read access for the analytics role on the tables added in V4-V6. V3's default
-- privileges only apply when Flyway runs as the role that ran V3, so grant explicitly, and keep
-- payment tokens and raw webhook payloads out of reach of analytics entirely.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'gym_analytics_ro') THEN
        EXECUTE 'GRANT SELECT ON payment_intents, member_credit_ledger, autopay_attempts, class_types, class_sessions,'
             || ' class_bookings, leads, lead_activities TO gym_analytics_ro';
        EXECUTE 'REVOKE SELECT ON saved_payment_methods, webhook_events FROM gym_analytics_ro';
        EXECUTE 'GRANT SELECT (id, member_id, provider, type, status, mandate_status, is_default, created_at)'
             || ' ON saved_payment_methods TO gym_analytics_ro';
    END IF;
END
$$;
