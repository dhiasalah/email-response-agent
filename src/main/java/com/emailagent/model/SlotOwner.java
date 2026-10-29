package com.emailagent.model;

/** Who can provide the missing value: the mailbox owner, or the sender (clarification, design doc §7.5). */
public enum SlotOwner {
    USER,
    SENDER
}
