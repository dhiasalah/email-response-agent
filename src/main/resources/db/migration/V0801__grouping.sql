-- Clusters of questions waiting on the same input = input requests (cards) for the owner (design doc §5, §7.2-7.4)

CREATE TABLE input_request (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slot_type       VARCHAR(40)   NOT NULL
        CHECK (slot_type IN ('GRADES_PUBLICATION_DATE','EXAM_DATE','EXAM_ROOM','DEADLINE','PROCEDURE',
                             'DOCUMENT_AVAILABILITY','MEETING_AVAILABILITY','INDIVIDUAL_DECISION','INDIVIDUAL_RESULT')),
    entity_key      VARCHAR(200)  NOT NULL,
    qualifier_key   VARCHAR(200),
    scope_program   VARCHAR(100),
    scope_track     VARCHAR(100),
    scope_year      VARCHAR(20),
    -- personal: one sender's own question; the card answers that email only and nothing else may join it
    personal        BOOLEAN       NOT NULL,
    question        VARCHAR(500)  NOT NULL,
    field_type      VARCHAR(20)   NOT NULL CHECK (field_type IN ('DATE','TEXT','YES_NO','TIME_SLOTS')),
    sample_question VARCHAR(500),
    centroid        REAL[],
    embedding_model VARCHAR(100),
    member_count    INT           NOT NULL DEFAULT 0 CHECK (member_count >= 0),
    proposed_value  VARCHAR(2000),
    proposal_source VARCHAR(500),
    priority        DOUBLE PRECISION NOT NULL DEFAULT 0 CHECK (priority >= 0 AND priority <= 1),
    status          VARCHAR(20)   NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN','WAITING_INPUT','ANSWERED','CLOSED')),
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_activity   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    version         BIGINT        NOT NULL DEFAULT 0,
    -- a shared card needs a known audience and an answer that may be given to more than one person
    CONSTRAINT input_request_shared_chk CHECK (personal OR (
        scope_program IS NOT NULL AND scope_track IS NOT NULL AND scope_year IS NOT NULL
        AND slot_type IN ('GRADES_PUBLICATION_DATE','EXAM_DATE','EXAM_ROOM','DEADLINE','PROCEDURE',
                          'DOCUMENT_AVAILABILITY'))),
    CHECK ((proposed_value IS NULL) = (proposal_source IS NULL))
);

CREATE INDEX input_request_open_idx ON input_request (status, slot_type);

ALTER TABLE slot
    ADD CONSTRAINT slot_request_fk FOREIGN KEY (request_id) REFERENCES input_request (id),
    ADD COLUMN grouped_by VARCHAR(20) CHECK (grouped_by IN ('NEW','SAME_KEY','VERIFIED','PERSONAL')),
    ADD CONSTRAINT slot_grouped_chk CHECK ((request_id IS NULL) = (grouped_by IS NULL));

CREATE INDEX slot_request_idx ON slot (request_id);

-- Whatever the code or a model decided: a slot joins only a card of its own slot type and its sender's exact
-- scope, and a personal card never gets a second member.
CREATE FUNCTION slot_request_check() RETURNS trigger AS $$
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

CREATE TRIGGER slot_request_guard
    BEFORE INSERT OR UPDATE OF request_id ON slot
    FOR EACH ROW EXECUTE FUNCTION slot_request_check();
