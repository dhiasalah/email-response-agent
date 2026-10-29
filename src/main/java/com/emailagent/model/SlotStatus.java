package com.emailagent.model;

/** PROPOSED = found in a semi-trusted source (calendar): the owner must confirm it (design doc §4.2). */
public enum SlotStatus {
    UNRESOLVED,
    PROPOSED,
    FILLED
}
