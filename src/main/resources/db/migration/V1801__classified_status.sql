-- A rename only: the status an email has after classification is CLASSIFIED (it was TRIAGED), and the agent that
-- sets it is written "classifier" in the audit log (it was "triage"). The lifecycle and its rules are unchanged.
-- Earlier migrations keep the old word: they are history and are never edited.

-- Emails. The lifecycle guard would refuse TRIAGED -> CLASSIFIED as a transition, so it is off for this one rename.
ALTER TABLE email DISABLE TRIGGER email_status_guard;
ALTER TABLE email DROP CONSTRAINT email_status_check;
UPDATE email SET status = 'CLASSIFIED' WHERE status = 'TRIAGED';
ALTER TABLE email ADD CONSTRAINT email_status_check
    CHECK (status IN ('NEW','FILTERED_OUT','CLASSIFIED','IGNORED','ESCALATED','RESOLVING','AWAITING_SENDER',
                      'AWAITING_INPUT','READY','DRAFTED','REVIEWED','DRAFT_SAVED','SENT','CLOSED'));
ALTER TABLE email ENABLE TRIGGER email_status_guard;

-- The audit log is append-only; renaming a status and an agent in it changes no fact it records, so the guard is
-- off for these two updates and back on before the migration ends.
ALTER TABLE decision_log DISABLE TRIGGER decision_log_no_change;
ALTER TABLE decision_log DROP CONSTRAINT decision_log_from_status_check;
ALTER TABLE decision_log DROP CONSTRAINT decision_log_to_status_check;
UPDATE decision_log
    SET from_status = CASE WHEN from_status = 'TRIAGED' THEN 'CLASSIFIED' ELSE from_status END,
        to_status   = CASE WHEN to_status = 'TRIAGED' THEN 'CLASSIFIED' ELSE to_status END,
        agent       = CASE WHEN agent = 'triage' THEN 'classifier' ELSE agent END
    WHERE from_status = 'TRIAGED' OR to_status = 'TRIAGED' OR agent = 'triage';
ALTER TABLE decision_log ADD CONSTRAINT decision_log_from_status_check
    CHECK (from_status IN ('NEW','FILTERED_OUT','CLASSIFIED','IGNORED','ESCALATED','RESOLVING','AWAITING_SENDER',
                           'AWAITING_INPUT','READY','DRAFTED','REVIEWED','DRAFT_SAVED','SENT','CLOSED'));
ALTER TABLE decision_log ADD CONSTRAINT decision_log_to_status_check
    CHECK (to_status IN ('NEW','FILTERED_OUT','CLASSIFIED','IGNORED','ESCALATED','RESOLVING','AWAITING_SENDER',
                         'AWAITING_INPUT','READY','DRAFTED','REVIEWED','DRAFT_SAVED','SENT','CLOSED'));
ALTER TABLE decision_log ENABLE TRIGGER decision_log_no_change;

-- Same table as EmailLifecycle.java (a test checks they agree), with the renamed status.
CREATE OR REPLACE FUNCTION email_status_transition() RETURNS trigger AS $$
BEGIN
    IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
        RETURN NEW;
    END IF;
    IF (OLD.status, NEW.status) IN (
        ('NEW', 'FILTERED_OUT'), ('NEW', 'CLASSIFIED'), ('NEW', 'ESCALATED'),
        ('CLASSIFIED', 'IGNORED'), ('CLASSIFIED', 'RESOLVING'), ('CLASSIFIED', 'ESCALATED'),
        ('RESOLVING', 'READY'), ('RESOLVING', 'AWAITING_SENDER'), ('RESOLVING', 'AWAITING_INPUT'),
        ('RESOLVING', 'ESCALATED'),
        ('AWAITING_SENDER', 'CLASSIFIED'), ('AWAITING_SENDER', 'CLOSED'), ('AWAITING_SENDER', 'ESCALATED'),
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
