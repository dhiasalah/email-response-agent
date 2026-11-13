package com.emailagent.store;

import com.emailagent.model.SenderScope;
import com.emailagent.model.SlotKey;
import java.time.Instant;

/**
 * The curator's decision about an owner's answer: the fact to store, or why nothing is stored ({@code store} false).
 * Knowledge memory writes it; it lives here so the store doesn't depend on the agent that decides. {@code agentId}
 * is the specialized agent whose memory receives the fact.
 */
public record FactDecision(boolean store, String reason, String agentId, SlotKey key, SenderScope scope,
        String value, Instant validUntil, String source, Long requestId) {

    public static FactDecision none(String reason) {
        return new FactDecision(false, reason, null, null, null, null, null, null, null);
    }
}
