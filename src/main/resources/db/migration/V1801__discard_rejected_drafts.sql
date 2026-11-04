-- A rejected draft leaves the owner's mailbox too (design doc §7.6 "approve, edit or reject"). In draft mode the
-- reviewed reply is already in the mailbox's Drafts when the owner rejects it: without this, the rejected text would
-- stay one click from being sent from Gmail. Same pattern as the outbox: the reject step writes the request in its
-- own transaction, the relay removes the draft afterwards and marks the row done. At least once, so a crash or a
-- Gmail error retries it, and removing a draft that is already gone does nothing.

CREATE TABLE mailbox_discard (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email_id   BIGINT        NOT NULL REFERENCES email (id),
    -- the outbox row that put the draft in the mailbox: its Message-ID finds the draft again
    outbox_id  BIGINT        NOT NULL UNIQUE REFERENCES outbox (id),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    done_at    TIMESTAMP(6) WITH TIME ZONE
);

CREATE INDEX mailbox_discard_pending_idx ON mailbox_discard (id) WHERE done_at IS NULL;

-- only a reply draft of the same email, saved as a draft (never a sent reply, never a notice), and nothing changes
-- afterwards except the time it was done
CREATE FUNCTION mailbox_discard_check() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('emailagent.retention', true) = 'on' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM outbox o WHERE o.id = NEW.outbox_id AND o.email_id = NEW.email_id
                       AND o.kind = 'DRAFT' AND o.draft_id IS NOT NULL) THEN
            RAISE EXCEPTION 'mailbox_discard: only a saved reply draft of this email';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.done_at IS NULL AND NEW.done_at IS NOT NULL
            AND to_jsonb(NEW) - 'done_at' = to_jsonb(OLD) - 'done_at' THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'mailbox_discard rows cannot be changed or deleted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER mailbox_discard_guard
    BEFORE INSERT OR UPDATE OR DELETE ON mailbox_discard
    FOR EACH ROW EXECUTE FUNCTION mailbox_discard_check();
