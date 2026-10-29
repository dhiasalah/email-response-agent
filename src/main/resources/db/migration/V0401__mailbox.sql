-- Lesson 04: what the mailbox import adds to each email

ALTER TABLE email
    ADD COLUMN sender_name     VARCHAR(200),
    ADD COLUMN sanitizer_flags VARCHAR(500),
    ADD COLUMN filter_reason   VARCHAR(30)
        CHECK (filter_reason IN ('AUTO_REPLY','MAILING_LIST','NO_REPLY_SENDER','OWN_ADDRESS','TOO_LARGE','RATE_LIMITED')),
    -- a filtered email always says why, and only filtered emails have a reason
    ADD CONSTRAINT email_filter_reason_chk
        CHECK ((status = 'FILTERED_OUT') = (filter_reason IS NOT NULL));
