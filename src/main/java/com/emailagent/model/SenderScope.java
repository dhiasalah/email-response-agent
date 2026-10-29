package com.emailagent.model;

/** Who the sender is, for memory reuse (design doc §8). Unknown scope disables reuse. */
public record SenderScope(String program, String track, String year) {

    public static final SenderScope UNKNOWN = new SenderScope(null, null, null);

    public boolean isKnown() {
        return program != null && track != null && year != null;
    }
}
