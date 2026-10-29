-- Emails, their slots, and the append-only decision log (design doc §5)

CREATE TABLE email (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id    VARCHAR(998)  NOT NULL UNIQUE,
    thread_id     VARCHAR(998),
    sender        VARCHAR(320)  NOT NULL,
    subject       VARCHAR(998),
    body          TEXT,
    received_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    scope_program VARCHAR(100),
    scope_track   VARCHAR(100),
    scope_year    VARCHAR(20),
    intent        VARCHAR(30)
        CHECK (intent IN ('QUESTION','REQUEST','MEETING','INFORMATION','COMPLAINT','OTHER')),
    sensitivity   VARCHAR(30)
        CHECK (sensitivity IN ('LOW','PERSONAL','COMPLAINT','LEGAL','HEALTH')),
    language      VARCHAR(5)
        CHECK (language IN ('FR','EN')),
    status        VARCHAR(30)   NOT NULL DEFAULT 'NEW'
        CHECK (status IN ('NEW','FILTERED_OUT','TRIAGED','IGNORED','ESCALATED','RESOLVING','AWAITING_SENDER',
                          'AWAITING_INPUT','READY','DRAFTED','REVIEWED','DRAFT_SAVED','SENT','CLOSED')),
    version       BIGINT        NOT NULL DEFAULT 0,
    updated_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE INDEX email_status_idx ON email (status);

CREATE TABLE slot (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email_id   BIGINT        NOT NULL REFERENCES email (id),
    type       VARCHAR(40)   NOT NULL
        CHECK (type IN ('GRADES_PUBLICATION_DATE','EXAM_DATE','EXAM_ROOM','DEADLINE','PROCEDURE',
                        'DOCUMENT_AVAILABILITY','MEETING_AVAILABILITY','INDIVIDUAL_DECISION','INDIVIDUAL_RESULT')),
    entity     VARCHAR(200)  NOT NULL,
    qualifier  VARCHAR(200),
    owner      VARCHAR(10)   NOT NULL CHECK (owner IN ('USER','SENDER')),
    status     VARCHAR(20)   NOT NULL DEFAULT 'UNRESOLVED'
        CHECK (status IN ('UNRESOLVED','PROPOSED','FILLED')),
    value      VARCHAR(2000),
    source     VARCHAR(500),
    request_id BIGINT,
    -- a filled or proposed slot always says where its value came from (grounding, design doc §4.4)
    CHECK (status = 'UNRESOLVED' OR (value IS NOT NULL AND source IS NOT NULL))
);

CREATE INDEX slot_email_idx ON slot (email_id);

CREATE TABLE decision_log (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email_id      BIGINT REFERENCES email (id),
    agent         VARCHAR(50)   NOT NULL,
    input_summary VARCHAR(1000),
    output        TEXT,
    confidence    DOUBLE PRECISION,
    sources       VARCHAR(2000),
    model_version VARCHAR(100),
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE INDEX decision_log_email_idx ON decision_log (email_id);

-- Append-only: no UPDATE, DELETE or TRUNCATE, even with raw SQL that bypasses Hibernate
CREATE FUNCTION decision_log_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'decision_log is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER decision_log_no_change
    BEFORE UPDATE OR DELETE ON decision_log
    FOR EACH ROW EXECUTE FUNCTION decision_log_append_only();

CREATE TRIGGER decision_log_no_truncate
    BEFORE TRUNCATE ON decision_log
    FOR EACH STATEMENT EXECUTE FUNCTION decision_log_append_only();
