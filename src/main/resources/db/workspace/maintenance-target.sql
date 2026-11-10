-- Optional maintenance target extension. Explicitly provision only a new independent target.
-- Not a Flyway migration and never executed by the importer or ordinary startup.
-- The application role needs SELECT,INSERT on this table and EXECUTE on
-- pg_catalog.pg_control_system(); it must not own the table/schema or have UPDATE/DELETE/TRUNCATE.
CREATE TABLE workspace.maintenance_import (
    singleton integer PRIMARY KEY CHECK (singleton = 1),
    operation text NOT NULL CHECK (operation ~ '^[a-f0-9]{32}$')
);
REVOKE ALL ON workspace.maintenance_import FROM PUBLIC;
