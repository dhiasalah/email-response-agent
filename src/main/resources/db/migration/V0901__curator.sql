-- What the curator adds: where a fact came from, its version chain, and which sent replies fed the recent answer log

ALTER TABLE fact
    ADD COLUMN request_id       BIGINT REFERENCES input_request (id),
    ADD COLUMN previous_fact_id BIGINT REFERENCES fact (id),
    -- a new version always points at the one it replaced
    ADD CONSTRAINT fact_version_chain_chk CHECK ((fact_version = 1) = (previous_fact_id IS NULL));

ALTER TABLE recent_answer
    ADD COLUMN source_reply_id VARCHAR(998),
    -- a later reply from the owner that says something else retires this entry (design doc §8.4)
    ADD COLUMN superseded_at   TIMESTAMP(6) WITH TIME ZONE;

-- Sent replies already read, so a reply is learned from once (and the model is asked once)
CREATE TABLE processed_reply (
    message_id   VARCHAR(998) PRIMARY KEY,
    email_id     BIGINT REFERENCES email (id),
    pairs        INT          NOT NULL CHECK (pairs >= 0),
    processed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
