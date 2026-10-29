-- The email lifecycle is enforced by the database too, and the audit log records each transition

ALTER TABLE decision_log
    ADD COLUMN from_status VARCHAR(30)
        CHECK (from_status IN ('NEW','FILTERED_OUT','TRIAGED','IGNORED','ESCALATED','RESOLVING','AWAITING_SENDER',
                               'AWAITING_INPUT','READY','DRAFTED','REVIEWED','DRAFT_SAVED','SENT','CLOSED')),
    ADD COLUMN to_status   VARCHAR(30)
        CHECK (to_status IN ('NEW','FILTERED_OUT','TRIAGED','IGNORED','ESCALATED','RESOLVING','AWAITING_SENDER',
                             'AWAITING_INPUT','READY','DRAFTED','REVIEWED','DRAFT_SAVED','SENT','CLOSED'));

-- Same table as EmailLifecycle.java (a test checks they agree). A bug, a raw SQL query or a later web panel
-- can't move an email from NEW straight to SENT and skip the reviewer.
CREATE FUNCTION email_status_transition() RETURNS trigger AS $$
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
        ('ESCALATED', 'READY'), ('ESCALATED', 'CLOSED')
    ) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'illegal status transition % -> %', OLD.status, NEW.status;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER email_status_guard
    BEFORE UPDATE OF status ON email
    FOR EACH ROW EXECUTE FUNCTION email_status_transition();
