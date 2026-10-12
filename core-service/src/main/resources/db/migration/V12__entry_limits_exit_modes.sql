-- Owner-configurable entry rules:
--  * a plan can limit how many times a member may enter per day (NULL = unlimited);
--  * each branch chooses how people leave: BUTTON (free exit: only entry is verified) or VERIFIED (scan to exit,
--    anti-passback); exit-button presses are logged as anonymous EXIT events.
ALTER TABLE membership_plans ADD COLUMN max_entries_per_day integer
    CHECK (max_entries_per_day IS NULL OR max_entries_per_day BETWEEN 1 AND 20);

CREATE TABLE access_branch_settings (
    branch_id   bigint      PRIMARY KEY REFERENCES gym_branches (id),
    exit_mode   varchar(20) NOT NULL CHECK (exit_mode IN ('BUTTON', 'VERIFIED')),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  bigint
);

ALTER TABLE access_events DROP CONSTRAINT access_events_credential_type_check;
ALTER TABLE access_events ADD CONSTRAINT access_events_credential_type_check
    CHECK (credential_type IN ('FINGERPRINT', 'FACE', 'RFID_CARD', 'QR', 'EXIT_BUTTON'));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'gym_analytics_ro') THEN
        EXECUTE 'GRANT SELECT ON access_branch_settings TO gym_analytics_ro';
    END IF;
END
$$;
