package com.emailagent.model;

/**
 * The registered slot taxonomy (design doc §7.1). Triage may only use these values.
 * The two flags are policy, enforced by code, whatever an LLM says.
 */
public enum SlotType {
    GRADES_PUBLICATION_DATE(FieldType.DATE, true, true),
    EXAM_DATE(FieldType.DATE, true, true),
    EXAM_ROOM(FieldType.TEXT, true, true),
    DEADLINE(FieldType.DATE, true, true),
    PROCEDURE(FieldType.TEXT, true, true),
    DOCUMENT_AVAILABILITY(FieldType.YES_NO, true, true),
    MEETING_AVAILABILITY(FieldType.TIME_SLOTS, false, true),
    INDIVIDUAL_DECISION(FieldType.YES_NO, false, true),
    INDIVIDUAL_RESULT(FieldType.TEXT, false, false);

    private final FieldType fieldType;
    private final boolean shareable;
    private final boolean automatable;

    SlotType(FieldType fieldType, boolean shareable, boolean automatable) {
        this.fieldType = fieldType;
        this.shareable = shareable;
        this.automatable = automatable;
    }

    public FieldType fieldType() {
        return fieldType;
    }

    /** Can an answer become a fact reused for other senders? */
    public boolean shareable() {
        return shareable;
    }

    /** Can a reply using this slot ever be produced by the agents? INDIVIDUAL_RESULT: never. */
    public boolean automatable() {
        return automatable;
    }
}
