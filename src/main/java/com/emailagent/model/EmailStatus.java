package com.emailagent.model;

/** Where an email is in its lifecycle (design doc §6). Only the orchestrator changes it. */
public enum EmailStatus {
    NEW,
    FILTERED_OUT,
    CLASSIFIED,
    IGNORED,
    ESCALATED,
    RESOLVING,
    AWAITING_SENDER,
    AWAITING_INPUT,
    READY,
    DRAFTED,
    REVIEWED,
    DRAFT_SAVED,
    SENT,
    CLOSED
}
