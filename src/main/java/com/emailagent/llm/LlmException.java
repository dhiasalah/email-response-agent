package com.emailagent.llm;

/**
 * The model gave no usable answer. Checked on purpose: every agent must decide what failure means (escalate).
 * The message never contains the model's output: it can hold personal data or an attacker's text.
 */
public class LlmException extends Exception {

    public enum Reason {
        INVALID_OUTPUT,
        TIMEOUT,
        UNAVAILABLE
    }

    private final Reason reason;

    public LlmException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
