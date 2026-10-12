-- The analytics service connects with a dedicated, read-only database role.
-- The role itself (and its password) is provisioned by infrastructure, never by a migration,
-- so no credential lives in source control. If the role exists, grant it SELECT access.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'gym_analytics_ro') THEN
        EXECUTE 'GRANT USAGE ON SCHEMA public TO gym_analytics_ro';
        EXECUTE 'GRANT SELECT ON ALL TABLES IN SCHEMA public TO gym_analytics_ro';
        -- no access to credentials / tokens; only the non-sensitive user columns analytics needs
        EXECUTE 'REVOKE SELECT ON users, refresh_tokens FROM gym_analytics_ro';
        EXECUTE 'GRANT SELECT (id, full_name, status, created_at) ON users TO gym_analytics_ro';
        EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO gym_analytics_ro';
    END IF;
END
$$;
