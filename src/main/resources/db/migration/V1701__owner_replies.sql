-- The owner edits a draft or writes the reply to an escalated email from the panel (design doc §6 "escalated →
-- user provides input", §7.6 "approve, edit or reject"). The owner's text is the owner's own reply: it gets an
-- owner review instead of the reviewer's, and both rows say so, so the audit never mixes the two.

ALTER TABLE reply_draft
    ADD COLUMN written_by VARCHAR(10) NOT NULL DEFAULT 'WRITER' CHECK (written_by IN ('WRITER','OWNER'));

ALTER TABLE reply_review
    ADD COLUMN reviewed_by VARCHAR(10) NOT NULL DEFAULT 'REVIEWER' CHECK (reviewed_by IN ('REVIEWER','OWNER'));

-- an owner review only ever approves, and only a draft the owner wrote or edited: the owner can't
-- "pass" a model's draft without opening it
CREATE FUNCTION reply_review_owner_check() RETURNS trigger AS $$
BEGIN
    IF NEW.reviewed_by = 'OWNER' AND (NOT NEW.passed OR NOT EXISTS (
            SELECT 1 FROM reply_draft d WHERE d.id = NEW.draft_id AND d.written_by = 'OWNER')) THEN
        RAISE EXCEPTION 'reply_review: an owner review approves a draft the owner wrote';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER reply_review_owner_guard
    BEFORE INSERT ON reply_review
    FOR EACH ROW EXECUTE FUNCTION reply_review_owner_check();

-- the lifecycle gains one edge: ESCALATED → SENT, the owner's reply to an escalated email. The outbox guard still
-- requires a passed review of the current, unchanged draft, so this edge can't skip a review either.
CREATE OR REPLACE FUNCTION email_status_transition() RETURNS trigger AS $$
BEGIN
    IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
        RETURN NEW;
    END IF;
    IF (OLD.status, NEW.status) IN (
        ('NEW', 'FILTERED_OUT'), ('NEW', 'TRIAGED'), ('NEW', 'ESCALATED'),
        ('TRIAGED', 'IGNORED'), ('TRIAGED', 'RESOLVING'), ('TRIAGED', 'ESCALATED'),
        ('RESOLVING', 'READY'), ('RESOLVING', 'AWAITING_SENDER'), ('RESOLVING', 'AWAITING_INPUT'),
        ('RESOLVING', 'ESCALATED'),
        ('AWAITING_SENDER', 'TRIAGED'), ('AWAITING_SENDER', 'CLOSED'), ('AWAITING_SENDER', 'ESCALATED'),
        ('AWAITING_INPUT', 'READY'), ('AWAITING_INPUT', 'ESCALATED'),
        ('READY', 'DRAFTED'), ('READY', 'ESCALATED'),
        ('DRAFTED', 'REVIEWED'), ('DRAFTED', 'ESCALATED'),
        ('REVIEWED', 'SENT'), ('REVIEWED', 'DRAFT_SAVED'), ('REVIEWED', 'ESCALATED'),
        ('DRAFT_SAVED', 'SENT'), ('DRAFT_SAVED', 'ESCALATED'),
        ('ESCALATED', 'READY'), ('ESCALATED', 'SENT'), ('ESCALATED', 'CLOSED')
    ) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'illegal status transition % -> %', OLD.status, NEW.status;
END;
$$ LANGUAGE plpgsql;
