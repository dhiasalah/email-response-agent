-- Follow-ups (design doc §6 timeouts, §7.5, §7.7): clarification questions, holding replies and broadcasts, written
-- by code from fixed templates, and the second way into the outbox

-- the sender answered a clarification: set by the importer, cleared when triage runs again on the merged email
ALTER TABLE email ADD COLUMN sender_replied BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE email DROP CONSTRAINT email_filter_reason_check;
ALTER TABLE email ADD CONSTRAINT email_filter_reason_values_chk
    CHECK (filter_reason IN ('AUTO_REPLY','MAILING_LIST','NO_REPLY_SENDER','OWN_ADDRESS','TOO_LARGE','RATE_LIMITED',
                             'MERGED_REPLY'));

-- a broadcast fact is not suggested twice
ALTER TABLE fact ADD COLUMN broadcast_at TIMESTAMP(6) WITH TIME ZONE;

CREATE TABLE notice (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind        VARCHAR(15)   NOT NULL CHECK (kind IN ('CLARIFICATION','HOLDING','BROADCAST')),
    email_id    BIGINT        REFERENCES email (id),
    request_id  BIGINT        REFERENCES input_request (id),
    round       INT           CHECK (round BETWEEN 1 AND 2),
    recipient   VARCHAR(320)  NOT NULL,
    subject     VARCHAR(998)  NOT NULL CHECK (subject !~ '[\r\n]'),
    body        VARCHAR(6000) NOT NULL,
    created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    approved_at TIMESTAMP(6) WITH TIME ZONE,
    answered_at TIMESTAMP(6) WITH TIME ZONE,
    -- a broadcast answers a card and goes to a list; the others answer one email
    CHECK ((kind = 'BROADCAST') = (request_id IS NOT NULL)),
    CHECK ((kind = 'BROADCAST') = (email_id IS NULL)),
    CHECK ((kind = 'CLARIFICATION') = (round IS NOT NULL)),
    CHECK (answered_at IS NULL OR kind = 'CLARIFICATION'),
    CHECK (approved_at IS NULL OR kind = 'BROADCAST')
);

CREATE UNIQUE INDEX notice_clarification_round_idx ON notice (email_id, round) WHERE kind = 'CLARIFICATION';
CREATE UNIQUE INDEX notice_one_holding_idx ON notice (email_id) WHERE kind = 'HOLDING';
CREATE UNIQUE INDEX notice_one_broadcast_idx ON notice (request_id) WHERE kind = 'BROADCAST';

CREATE FUNCTION notice_check() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.email_id IS NOT NULL
                AND NOT EXISTS (SELECT 1 FROM email e WHERE e.id = NEW.email_id AND e.sender = NEW.recipient) THEN
            RAISE EXCEPTION 'notice: a follow-up goes to the sender of the email';
        END IF;
        RETURN NEW;
    END IF;
    -- the text never changes; approval and answer are recorded once
    IF TG_OP = 'UPDATE'
            AND to_jsonb(NEW) - 'approved_at' - 'answered_at' = to_jsonb(OLD) - 'approved_at' - 'answered_at'
            AND (OLD.approved_at IS NULL OR NEW.approved_at IS NOT DISTINCT FROM OLD.approved_at)
            AND (OLD.answered_at IS NULL OR NEW.answered_at IS NOT DISTINCT FROM OLD.answered_at) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'notice rows cannot be rewritten or deleted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER notice_guard
    BEFORE INSERT OR UPDATE OR DELETE ON notice
    FOR EACH ROW EXECUTE FUNCTION notice_check();

-- the outbox carries either a reviewed reply or a notice, never both, never neither
ALTER TABLE outbox
    ALTER COLUMN email_id DROP NOT NULL,
    ALTER COLUMN draft_id DROP NOT NULL,
    ALTER COLUMN review_id DROP NOT NULL,
    ALTER COLUMN in_reply_to DROP NOT NULL,
    ADD COLUMN notice_id BIGINT REFERENCES notice (id),
    ADD CONSTRAINT outbox_reply_or_notice_chk CHECK (
        (notice_id IS NULL) = (draft_id IS NOT NULL AND review_id IS NOT NULL AND email_id IS NOT NULL
                               AND in_reply_to IS NOT NULL)),
    ADD CONSTRAINT outbox_notice_only_chk CHECK (notice_id IS NULL OR (draft_id IS NULL AND review_id IS NULL));

DROP INDEX outbox_one_send_idx;
CREATE UNIQUE INDEX outbox_one_send_idx ON outbox (email_id) WHERE kind = 'SEND' AND notice_id IS NULL;
CREATE UNIQUE INDEX outbox_one_per_notice_idx ON outbox (notice_id) WHERE notice_id IS NOT NULL;

CREATE OR REPLACE FUNCTION outbox_check() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.email_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM email e
                       WHERE e.id = NEW.email_id AND e.sender = NEW.recipient AND e.message_id = NEW.in_reply_to) THEN
            RAISE EXCEPTION 'outbox: a reply goes to the sender of the email, in its thread';
        END IF;
        IF NEW.notice_id IS NULL THEN
            IF NOT EXISTS (SELECT 1 FROM reply_review r JOIN reply_draft d ON d.id = r.draft_id
                           WHERE r.id = NEW.review_id AND r.passed AND r.email_id = NEW.email_id
                             AND d.id = NEW.draft_id AND d.current AND d.body = NEW.body) THEN
                RAISE EXCEPTION 'outbox: only the current draft, unchanged, after a passed review';
            END IF;
        ELSIF NOT EXISTS (SELECT 1 FROM notice n
                          WHERE n.id = NEW.notice_id AND n.recipient = NEW.recipient AND n.subject = NEW.subject
                            AND n.body = NEW.body AND n.email_id IS NOT DISTINCT FROM NEW.email_id
                            AND (n.kind <> 'BROADCAST' OR (n.approved_at IS NOT NULL AND NEW.kind = 'SEND'))) THEN
            RAISE EXCEPTION 'outbox: a notice goes out as written, and a broadcast only after approval';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.delivered_at IS NULL AND NEW.delivered_at IS NOT NULL
            AND to_jsonb(NEW) - 'delivered_at' = to_jsonb(OLD) - 'delivered_at' THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'outbox rows cannot be changed or deleted';
END;
$$ LANGUAGE plpgsql;
