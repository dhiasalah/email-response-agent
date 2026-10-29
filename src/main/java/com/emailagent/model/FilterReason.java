package com.emailagent.model;

/** Why the deterministic pre-filter dropped an email before any LLM saw it (lesson 04). */
public enum FilterReason {
    AUTO_REPLY,
    MAILING_LIST,
    NO_REPLY_SENDER,
    OWN_ADDRESS,
    TOO_LARGE,
    RATE_LIMITED
}
