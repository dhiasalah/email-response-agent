package com.emailagent.model;

/** What the mailbox does with an outgoing reply (design doc §9, send modes). */
public enum OutboxKind {
    /** Saved in the owner's drafts: the owner sends it. */
    DRAFT,
    /** Sent to the original sender. */
    SEND
}
