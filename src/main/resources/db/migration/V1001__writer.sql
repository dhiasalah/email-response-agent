-- Reply drafts (design doc §4.4, §16.6): one row per writing, older ones kept when a reply is rewritten

CREATE TABLE reply_draft (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email_id      BIGINT        NOT NULL REFERENCES email (id),
    body          VARCHAR(6000) NOT NULL,
    language      VARCHAR(5)    NOT NULL CHECK (language IN ('FR','EN')),
    -- every fact the reply rests on, with its source: what the reviewer checks the text against
    facts_used    VARCHAR(2000) NOT NULL,
    model_version VARCHAR(100),
    attempts      INT           NOT NULL CHECK (attempts BETWEEN 1 AND 2),
    current       BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

-- at most one current draft per email
CREATE UNIQUE INDEX reply_draft_current_idx ON reply_draft (email_id) WHERE current;
