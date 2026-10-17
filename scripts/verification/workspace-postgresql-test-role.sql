-- Run interactively with psql against the existing test database. Never put passwords in SQL.
\set ON_ERROR_STOP on
\encoding UTF8
BEGIN;
DO $$
BEGIN
    IF current_database() <> 'personal_ai_workspace_test'
       OR inet_server_addr() <> '127.0.0.1'::inet OR inet_server_port() <> 5432
       OR current_setting('server_version_num')::int NOT BETWEEN 170000 AND 179999
       OR lower(replace(current_setting('data_directory'), chr(92), '/')) <> 'd:/postgresql/data'
       OR current_user <> 'postgres' THEN
        RAISE EXCEPTION '已停止：测试数据库实例或管理员身份与预期不符';
    END IF;
    IF (SELECT count(*) FROM public.flyway_schema_history WHERE success) <> 21
       OR EXISTS (SELECT FROM pg_namespace WHERE nspname = 'workspace')
       OR EXISTS (SELECT FROM pg_roles WHERE rolname IN
          ('personal_ai_workspace_b1_app','personal_ai_workspace_b1_migrator')) THEN
        RAISE EXCEPTION '已停止：Finance 迁移记录与预期不符，或 Workspace schema/角色已存在；请先检查，不会覆盖现有状态';
    END IF;
END;
$$;
CREATE ROLE personal_ai_workspace_b1_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOINHERIT NOREPLICATION NOBYPASSRLS;
CREATE ROLE personal_ai_workspace_b1_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOINHERIT NOREPLICATION NOBYPASSRLS;
GRANT CONNECT ON DATABASE personal_ai_workspace_test
    TO personal_ai_workspace_b1_migrator, personal_ai_workspace_b1_app;
CREATE SCHEMA workspace AUTHORIZATION personal_ai_workspace_b1_migrator;
REVOKE ALL ON SCHEMA workspace FROM PUBLIC;
GRANT USAGE ON SCHEMA workspace TO personal_ai_workspace_b1_app;
\echo 请为下面两个账号分别设置不同的强密码，每个密码需要输入两次；密码不会写入 SQL 文件。
\echo 第一步：设置 Workspace 迁移账号 personal_ai_workspace_b1_migrator 的密码。
\password personal_ai_workspace_b1_migrator
\echo 第二步：设置 Workspace 应用账号 personal_ai_workspace_b1_app 的密码。
\password personal_ai_workspace_b1_app
COMMIT;
