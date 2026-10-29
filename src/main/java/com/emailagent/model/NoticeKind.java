package com.emailagent.model;

/**
 * Messages the system writes itself, from fixed templates (design doc §6 timeouts, §7.5, §7.7). No model writes
 * any of them, so they need no reviewer: their only variable parts come from trusted sources.
 */
public enum NoticeKind {
    /** Asks the sender for the items only they can give. At most two rounds per email. */
    CLARIFICATION,
    /** Tells the sender the question is being checked, when a card stays unanswered. Once per email. */
    HOLDING,
    /** One announcement to a cohort's list instead of many replies. Sent only after the owner approves it. */
    BROADCAST
}
