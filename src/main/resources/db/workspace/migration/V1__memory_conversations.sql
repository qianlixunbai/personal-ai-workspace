-- Workspace-owned schema. Finance public migrations and history are independent.
CREATE TABLE workspace.storage_metadata (
    singleton integer PRIMARY KEY CHECK (singleton = 1), version integer NOT NULL CHECK (version = 1)
);
INSERT INTO workspace.storage_metadata VALUES (1, 1);
CREATE TABLE workspace.write_lock (singleton integer PRIMARY KEY CHECK (singleton = 1));
INSERT INTO workspace.write_lock VALUES (1);
CREATE TABLE workspace.memory_items (
    id text COLLATE "C" NOT NULL UNIQUE,
    type text NOT NULL CHECK (type IN ('PREFERENCE','PROJECT_NOTE')),
    title text NOT NULL, content text NOT NULL,
    status text NOT NULL CHECK (status IN ('ACTIVE','ARCHIVED')),
    revision bigint NOT NULL CHECK (revision > 0),
    source text NOT NULL CHECK (source = 'MANUAL'),
    created_at bigint NOT NULL, updated_at bigint NOT NULL
);
CREATE INDEX memory_items_order ON workspace.memory_items(status, updated_at DESC, id ASC);
CREATE TABLE workspace.conversations (
    id text COLLATE "C" PRIMARY KEY, title text NOT NULL,
    status text NOT NULL CHECK (status IN ('ACTIVE','ARCHIVED')),
    created_at bigint NOT NULL, updated_at bigint NOT NULL CHECK (updated_at >= created_at)
);
CREATE INDEX conversations_order ON workspace.conversations(status, updated_at DESC, id ASC);
CREATE TABLE workspace.conversation_turns (
    id text COLLATE "C" PRIMARY KEY,
    conversation_id text NOT NULL REFERENCES workspace.conversations(id) ON DELETE CASCADE,
    sequence bigint NOT NULL CHECK (sequence > 0),
    status text NOT NULL CHECK (status IN ('PENDING','SUCCEEDED','FAILED','CANCELLED','TIMED_OUT')),
    created_at bigint NOT NULL, updated_at bigint NOT NULL CHECK (updated_at >= created_at),
    task_id text UNIQUE,
    failure_code text CHECK (failure_code IN ('EXECUTION_INTERRUPTED','PROVIDER_UNAVAILABLE','MODEL_UNAVAILABLE',
        'QUEUE_FULL','POLICY_DENIED','EXECUTION_FAILED','STORAGE_UNAVAILABLE')),
    UNIQUE(conversation_id,sequence)
);
CREATE TABLE workspace.conversation_messages (
    id text COLLATE "C" PRIMARY KEY,
    turn_id text NOT NULL REFERENCES workspace.conversation_turns(id) ON DELETE CASCADE,
    role text NOT NULL CHECK (role IN ('USER','ASSISTANT')),
    content text NOT NULL, created_at bigint NOT NULL, UNIQUE(turn_id,role)
);
CREATE TABLE workspace.conversation_memory_selections (
    turn_id text NOT NULL REFERENCES workspace.conversation_turns(id) ON DELETE CASCADE,
    position integer NOT NULL CHECK (position BETWEEN 0 AND 3),
    memory_id text NOT NULL, revision bigint NOT NULL CHECK (revision > 0),
    PRIMARY KEY(turn_id,position), UNIQUE(turn_id,memory_id)
);
-- Selections intentionally retain historical IDs/revisions even after a Memory item is deleted.
CREATE FUNCTION workspace.reject_immutable_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'immutable Workspace record'; END;
$$;
CREATE TRIGGER conversation_message_immutable BEFORE UPDATE ON workspace.conversation_messages
    FOR EACH ROW EXECUTE FUNCTION workspace.reject_immutable_update();
CREATE TRIGGER conversation_selection_immutable BEFORE UPDATE ON workspace.conversation_memory_selections
    FOR EACH ROW EXECUTE FUNCTION workspace.reject_immutable_update();
CREATE TRIGGER conversation_terminal_immutable BEFORE UPDATE ON workspace.conversation_turns
    FOR EACH ROW WHEN (OLD.status <> 'PENDING') EXECUTE FUNCTION workspace.reject_immutable_update();
CREATE TABLE workspace.sqlite_import (
    singleton integer PRIMARY KEY CHECK (singleton = 1),
    source_digest text NOT NULL CHECK (source_digest ~ '^[a-f0-9]{64}$'),
    imported_at bigint NOT NULL
);
