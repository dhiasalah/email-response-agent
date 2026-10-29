-- Retention and erasure (design doc §13): after the retention period, or on a sender's request, an email keeps only
-- an anonymous tombstone (status, codes, dates) so the audit log stays whole; everything personal around it is deleted.
-- The guarded tables accept a delete only inside a transaction that declares itself the retention job
-- (SET LOCAL emailagent.retention = 'on'): a bug or a stray query elsewhere still can't remove a sent reply's record.

ALTER TABLE email ADD COLUMN erased_at TIMESTAMP(6) WITH TIME ZONE;

CREATE OR REPLACE FUNCTION reply_review_append_only() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('emailagent.retention', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'reply_review is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION notice_check() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('emailagent.retention', true) = 'on' THEN
        RETURN OLD;
    END IF;
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

CREATE OR REPLACE FUNCTION outbox_check() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('emailagent.retention', true) = 'on' THEN
        RETURN OLD;
    END IF;
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
