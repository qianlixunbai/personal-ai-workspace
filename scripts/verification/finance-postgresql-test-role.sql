-- Interactive administrator setup for the single authorized, empty test database.
-- No password literals: psql's local \password prompt sets the migration password.
\set ON_ERROR_STOP on
BEGIN;
DO $$
BEGIN
    IF current_database() <> 'personal_ai_workspace_test'
       OR inet_server_addr() <> '127.0.0.1'::inet OR inet_server_port() <> 5432
       OR current_setting('server_version_num')::int NOT BETWEEN 170000 AND 179999
       OR lower(replace(current_setting('data_directory'), chr(92), '/')) <> 'd:/postgresql/data'
       OR current_user <> 'postgres' THEN
        RAISE EXCEPTION 'STOP: unexpected PostgreSQL instance, database or administrator';
    END IF;
    IF EXISTS (SELECT FROM pg_namespace WHERE nspname <> 'public'
               AND nspname <> 'information_schema' AND nspname NOT LIKE 'pg_%')
       OR EXISTS (SELECT FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                  WHERE n.nspname = 'public')
       OR EXISTS (SELECT FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                  WHERE n.nspname = 'public')
       OR EXISTS (SELECT FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                  WHERE n.nspname = 'public')
       OR EXISTS (SELECT FROM pg_extension WHERE extname <> 'plpgsql') THEN
        RAISE EXCEPTION 'STOP: test database is not empty; no initialization permitted';
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'personal_ai_workspace_test_app'
                   AND rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole
                   AND NOT rolreplication AND NOT rolbypassrls)
       OR EXISTS (SELECT FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.member
                  WHERE r.rolname = 'personal_ai_workspace_test_app')
       OR EXISTS (SELECT FROM pg_database d JOIN pg_roles r ON r.oid = d.datdba
                  WHERE r.rolname = 'personal_ai_workspace_test_app')
       OR has_database_privilege('personal_ai_workspace_test_app', current_database(), 'CREATE')
       OR has_schema_privilege('personal_ai_workspace_test_app', 'public', 'CREATE') THEN
        RAISE EXCEPTION 'STOP: unexpected test application role privileges';
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'personal_ai_workspace_test_migrator') THEN
        RAISE EXCEPTION 'STOP: migration role already exists; inspect before reuse';
    END IF;
    IF NOT EXISTS (SELECT FROM pg_available_extension_versions
                   WHERE name = 'pgcrypto' AND installed = false AND trusted) THEN
        RAISE EXCEPTION 'STOP: trusted pgcrypto extension is unavailable';
    END IF;
END;
$$;

SELECT current_database(), current_user, inet_server_addr(), inet_server_port(),
       current_setting('server_version') AS server_version;
CREATE ROLE personal_ai_workspace_test_migrator LOGIN NOSUPERUSER NOCREATEDB
    NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
-- V13 requires temporary tables; V16 installs the trusted pgcrypto extension.
GRANT CONNECT, TEMPORARY, CREATE ON DATABASE personal_ai_workspace_test
    TO personal_ai_workspace_test_migrator;
GRANT USAGE, CREATE ON SCHEMA public TO personal_ai_workspace_test_migrator;
-- Identity validation needs this function, not membership in pg_monitor.
GRANT EXECUTE ON FUNCTION pg_catalog.pg_control_system() TO personal_ai_workspace_test_app;
\echo Choose a unique strong migration password (at least 16 characters).
\password personal_ai_workspace_test_migrator
COMMIT;
