package com.emailagent.llm;

import java.util.Objects;

/**
 * One call to a model. Trusted instructions and untrusted data are two separate fields, so they are never glued
 * into one string: implementations send {@code instructions} as the system message and {@code untrustedInput}
 * only as data (inside the untrusted envelope from lesson 03).
 */
public record LlmRequest(String agent, ModelTier tier, String instructions, String untrustedInput) {

    public static final int MAX_INSTRUCTIONS = 20_000;
    public static final int MAX_UNTRUSTED_INPUT = 60_000;

    public LlmRequest {
        if (agent == null || agent.isBlank() || agent.length() > 50) {
            throw new IllegalArgumentException("agent must be 1-50 characters");
        }
        Objects.requireNonNull(tier, "tier");
        if (instructions == null || instructions.isBlank() || instructions.length() > MAX_INSTRUCTIONS) {
            throw new IllegalArgumentException("instructions must be 1-" + MAX_INSTRUCTIONS + " characters");
        }
        if (untrustedInput == null || untrustedInput.length() > MAX_UNTRUSTED_INPUT) {
            throw new IllegalArgumentException("untrustedInput must be 0-" + MAX_UNTRUSTED_INPUT + " characters");
        }
    }
}
