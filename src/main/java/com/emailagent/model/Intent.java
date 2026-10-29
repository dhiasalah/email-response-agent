package com.emailagent.model;

/** What the sender wants. A closed list, so two similar emails always get the same label. */
public enum Intent {
    QUESTION,
    REQUEST,
    MEETING,
    INFORMATION,
    COMPLAINT,
    OTHER
}
