-- Knowledge truth and derived index; V1 tables and Finance public are unchanged.
CREATE TABLE workspace.knowledge_control(singleton integer PRIMARY KEY CHECK(singleton=1), format integer NOT NULL CHECK(format=1));
INSERT INTO workspace.knowledge_control VALUES(1,1);
CREATE TABLE workspace.knowledge_documents(
    id text COLLATE "C" PRIMARY KEY, title text NOT NULL,
    status text NOT NULL CHECK(status IN ('ACTIVE','ARCHIVED')),
    version bigint NOT NULL CHECK(version>0), current_revision bigint,
    created text NOT NULL, updated text NOT NULL, last_job text
);
CREATE TABLE workspace.knowledge_revisions(
    doc_id text REFERENCES workspace.knowledge_documents(id) ON DELETE CASCADE,
    revision bigint CHECK(revision BETWEEN 1 AND 10), digest text NOT NULL,
    filename text NOT NULL, type text NOT NULL CHECK(type IN ('TXT','MARKDOWN')),
    bytes bigint NOT NULL CHECK(bytes>0 AND bytes<=8388608), imported text NOT NULL,
    parser text NOT NULL, normalizer text NOT NULL, representation_digest text NOT NULL,
    text text NOT NULL, locators text NOT NULL, lines integer NOT NULL CHECK(lines BETWEEN 1 AND 100000),
    artifact_bytes bigint NOT NULL CHECK(artifact_bytes>0), PRIMARY KEY(doc_id,revision), UNIQUE(doc_id,digest)
);
ALTER TABLE workspace.knowledge_documents ADD CONSTRAINT knowledge_current_revision
    FOREIGN KEY(id,current_revision) REFERENCES workspace.knowledge_revisions(doc_id,revision) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX knowledge_revisions_digest ON workspace.knowledge_revisions(digest);
CREATE TRIGGER knowledge_revision_immutable BEFORE UPDATE ON workspace.knowledge_revisions
    FOR EACH ROW EXECUTE FUNCTION workspace.reject_immutable_update();
CREATE TABLE workspace.knowledge_jobs(
    id text COLLATE "C" PRIMARY KEY, doc_id text NOT NULL REFERENCES workspace.knowledge_documents(id) ON DELETE CASCADE,
    state text NOT NULL CHECK(state IN ('PENDING','PARSING','READY','FAILED','CANCELLED','INTERRUPTED')),
    error text, revision bigint NOT NULL, filename text NOT NULL, type text NOT NULL,
    size bigint NOT NULL CHECK(size>0 AND size<=8388608), expected_version bigint NOT NULL, created text NOT NULL
);
CREATE TABLE workspace.knowledge_deletes(doc_id text COLLATE "C" PRIMARY KEY, token text NOT NULL UNIQUE,
    revisions text NOT NULL, expected_version bigint NOT NULL);
CREATE TABLE workspace.knowledge_sqlite_import(singleton integer PRIMARY KEY CHECK(singleton=1),
    content_digest text NOT NULL, imported_at bigint NOT NULL);
CREATE TABLE workspace.knowledge_index_metadata(k text PRIMARY KEY, v text NOT NULL);
CREATE TABLE workspace.knowledge_chunks(
    id integer PRIMARY KEY, document text COLLATE "C" NOT NULL, revision bigint NOT NULL,
    ordinal integer NOT NULL, start_offset integer NOT NULL, end_offset integer NOT NULL,
    first_line integer NOT NULL, last_line integer NOT NULL, heading text,
    title_tokens text NOT NULL, heading_tokens text NOT NULL, body_tokens text NOT NULL,
    UNIQUE(document,revision,ordinal)
);
