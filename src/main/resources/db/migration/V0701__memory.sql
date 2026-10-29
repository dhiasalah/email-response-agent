-- Knowledge memory (design doc §8): facts confirmed by the owner, and the 7-day log of recent answers

CREATE TABLE fact (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slot_type     VARCHAR(40)   NOT NULL,
    entity_key    VARCHAR(200)  NOT NULL,
    qualifier_key VARCHAR(200),
    scope_program VARCHAR(100)  NOT NULL,
    scope_track   VARCHAR(100)  NOT NULL,
    scope_year    VARCHAR(20)   NOT NULL,
    value         VARCHAR(2000) NOT NULL,
    shareable     BOOLEAN       NOT NULL,
    valid_until   TIMESTAMP(6) WITH TIME ZONE,
    source        VARCHAR(500)  NOT NULL,
    fact_version  INT           NOT NULL DEFAULT 1 CHECK (fact_version >= 1),
    active        BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    -- personal slot types can never become a shareable fact, whatever the code above says
    CHECK (shareable = FALSE OR slot_type IN ('GRADES_PUBLICATION_DATE','EXAM_DATE','EXAM_ROOM','DEADLINE',
                                              'PROCEDURE','DOCUMENT_AVAILABILITY'))
);

-- one active fact per slot key and scope; older versions stay for corrections (who received the old value)
CREATE UNIQUE INDEX fact_active_key_idx
    ON fact (slot_type, entity_key, COALESCE(qualifier_key, ''), scope_program, scope_track, scope_year)
    WHERE active;

CREATE TABLE recent_answer (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slot_type       VARCHAR(40)   NOT NULL,
    entity_key      VARCHAR(200)  NOT NULL,
    qualifier_key   VARCHAR(200),
    scope_program   VARCHAR(100)  NOT NULL,
    scope_track     VARCHAR(100)  NOT NULL,
    scope_year      VARCHAR(20)   NOT NULL,
    value           VARCHAR(2000) NOT NULL,
    source_email_id BIGINT REFERENCES email (id),
    answered_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CHECK (slot_type IN ('GRADES_PUBLICATION_DATE','EXAM_DATE','EXAM_ROOM','DEADLINE','PROCEDURE',
                         'DOCUMENT_AVAILABILITY'))
);

CREATE INDEX recent_answer_key_idx ON recent_answer (slot_type, entity_key, scope_program, scope_track, scope_year);
