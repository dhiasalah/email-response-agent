-- Reviewer verdicts and the outbox (design doc §9, §11): only the reviewed, unchanged reply, to the original sender,
-- in its thread, can leave the system, and an email is answered at most once

CREATE TABLE reply_review (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email_id        BIGINT       NOT NULL REFERENCES email (id),
    draft_id        BIGINT       NOT NULL REFERENCES reply_draft (id),
    passed          BOOLEAN      NOT NULL,
    rule_failures   VARCHAR(500),
    model_issues    VARCHAR(500),
    outlier_reasons VARCHAR(500),
    model_version   VARCHAR(100),
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    -- a passed review has no failure of any kind
    CHECK (NOT passed OR (rule_failures IS NULL AND model_issues IS NULL))
);

CREATE INDEX reply_review_email_idx ON reply_review (email_id);

CREATE FUNCTION reply_review_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'reply_review is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER reply_review_no_change
    BEFORE UPDATE OR DELETE ON reply_review
    FOR EACH ROW EXECUTE FUNCTION reply_review_append_only();

CREATE TABLE outbox (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email_id     BIGINT        NOT NULL REFERENCES email (id),
    draft_id     BIGINT        NOT NULL REFERENCES reply_draft (id),
    review_id    BIGINT        NOT NULL REFERENCES reply_review (id),
    kind         VARCHAR(10)   NOT NULL CHECK (kind IN ('DRAFT','SEND')),
    recipient    VARCHAR(320)  NOT NULL,
    -- header injection: no line break can reach the mail headers
    subject      VARCHAR(998)  NOT NULL CHECK (subject !~ '[\r\n]'),
    body         VARCHAR(6000) NOT NULL,
    in_reply_to  VARCHAR(998)  NOT NULL,
    message_id   VARCHAR(998)  NOT NULL UNIQUE,
    created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    delivered_at TIMESTAMP(6) WITH TIME ZONE
);

-- whatever runs twice, an email gets at most one sent reply
CREATE UNIQUE INDEX outbox_one_send_idx ON outbox (email_id) WHERE kind = 'SEND';
CREATE INDEX outbox_pending_idx ON outbox (id) WHERE delivered_at IS NULL;
CREATE INDEX outbox_sent_idx ON outbox (recipient, created_at) WHERE kind = 'SEND';

CREATE FUNCTION outbox_check() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM email e
                       WHERE e.id = NEW.email_id AND e.sender = NEW.recipient AND e.message_id = NEW.in_reply_to) THEN
            RAISE EXCEPTION 'outbox: a reply goes to the sender of the email, in its thread';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM reply_review r JOIN reply_draft d ON d.id = r.draft_id
                       WHERE r.id = NEW.review_id AND r.passed AND r.email_id = NEW.email_id
                         AND d.id = NEW.draft_id AND d.current AND d.body = NEW.body) THEN
            RAISE EXCEPTION 'outbox: only the current draft, unchanged, after a passed review';
        END IF;
        RETURN NEW;
    END IF;
    -- the only change ever allowed: recording the delivery, once
    IF TG_OP = 'UPDATE' AND OLD.delivered_at IS NULL AND NEW.delivered_at IS NOT NULL
            AND to_jsonb(NEW) - 'delivered_at' = to_jsonb(OLD) - 'delivered_at' THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'outbox rows cannot be changed or deleted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER outbox_guard
    BEFORE INSERT OR UPDATE OR DELETE ON outbox
    FOR EACH ROW EXECUTE FUNCTION outbox_check();
