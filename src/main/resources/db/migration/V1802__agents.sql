-- Specialized agents (brief §4-5): every email is routed to one agent, and each agent has its own memory.
-- A fact, a recent answer or a card belongs to the agent whose emails produced it, and is only ever reused for
-- emails routed to that same agent. The agent id is the file name of its definition (agents/<id>.json).

-- null until the router has run (NEW, or escalated by the injection detector before routing)
ALTER TABLE email ADD COLUMN agent_id VARCHAR(50);
-- emails already past the router were handled in the single, student-only context of earlier versions
UPDATE email SET agent_id = 'student'
    WHERE status NOT IN ('NEW', 'FILTERED_OUT', 'CLASSIFIED')
      AND NOT (status = 'ESCALATED' AND NOT EXISTS (SELECT 1 FROM slot WHERE slot.email_id = email.id));

-- Rows written before agents existed all came from cohort-scoped student emails: they belong to the student agent.
ALTER TABLE fact ADD COLUMN agent_id VARCHAR(50) NOT NULL DEFAULT 'student';
ALTER TABLE fact ALTER COLUMN agent_id DROP DEFAULT;
ALTER TABLE recent_answer ADD COLUMN agent_id VARCHAR(50) NOT NULL DEFAULT 'student';
ALTER TABLE recent_answer ALTER COLUMN agent_id DROP DEFAULT;
ALTER TABLE input_request ADD COLUMN agent_id VARCHAR(50) NOT NULL DEFAULT 'student';
ALTER TABLE input_request ALTER COLUMN agent_id DROP DEFAULT;

-- one active fact per agent, slot key and scope
DROP INDEX fact_active_key_idx;
CREATE UNIQUE INDEX fact_active_key_idx
    ON fact (agent_id, slot_type, entity_key, COALESCE(qualifier_key, ''), scope_program, scope_track, scope_year)
    WHERE active;

DROP INDEX recent_answer_key_idx;
CREATE INDEX recent_answer_key_idx
    ON recent_answer (agent_id, slot_type, entity_key, scope_program, scope_track, scope_year);

-- The card guard of V0801, plus: a slot joins only a card of its own email's agent. One owner answer must never
-- travel from one agent's context into another's, whatever the code or a model decided.
CREATE OR REPLACE FUNCTION slot_request_check() RETURNS trigger AS $$
DECLARE
    req input_request%ROWTYPE;
    em  email%ROWTYPE;
BEGIN
    IF NEW.request_id IS NULL THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW.request_id IS NOT DISTINCT FROM OLD.request_id THEN
            RETURN NEW;
        END IF;
    END IF;
    SELECT * INTO req FROM input_request WHERE id = NEW.request_id;
    SELECT * INTO em FROM email WHERE id = NEW.email_id;
    IF req.slot_type <> NEW.type THEN
        RAISE EXCEPTION 'slot % cannot join a card of another slot type', NEW.id;
    END IF;
    IF em.agent_id IS DISTINCT FROM req.agent_id THEN
        RAISE EXCEPTION 'slot % cannot join a card of another agent', NEW.id;
    END IF;
    IF req.personal THEN
        IF EXISTS (SELECT 1 FROM slot WHERE request_id = req.id AND id <> NEW.id) THEN
            RAISE EXCEPTION 'personal card % already has a member', req.id;
        END IF;
    ELSIF (req.scope_program, req.scope_track, req.scope_year)
            IS DISTINCT FROM (em.scope_program, em.scope_track, em.scope_year) THEN
        RAISE EXCEPTION 'slot % cannot join a card of another scope', NEW.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
