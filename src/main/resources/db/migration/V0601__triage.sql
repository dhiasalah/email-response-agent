-- What triage and the injection detector add

ALTER TABLE email
    ADD COLUMN reply_needed      BOOLEAN,
    ADD COLUMN injection_score   DOUBLE PRECISION CHECK (injection_score >= 0),
    ADD COLUMN injection_signals VARCHAR(500);

-- the question as the sender asked it, the unit grouping clusters on (design doc §7.2)
ALTER TABLE slot
    ADD COLUMN question VARCHAR(500);
