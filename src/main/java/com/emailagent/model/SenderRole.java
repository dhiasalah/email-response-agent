package com.emailagent.model;

/**
 * A sender's role, from the trusted roster only (never from the display name or signature), and its weight in
 * card priority (design doc §7.4). Unknown senders weigh nothing: claiming to be the dean changes no ranking.
 */
public enum SenderRole {
    UNKNOWN(0.0),
    STUDENT(0.3),
    COLLEAGUE(0.6),
    ADMINISTRATION(0.8),
    MANAGEMENT(1.0);

    private final double weight;

    SenderRole(double weight) {
        this.weight = weight;
    }

    public double weight() {
        return weight;
    }
}
