package com.emailagent.agents;

import com.emailagent.model.SenderRole;
import com.emailagent.store.AgentRegistry;
import java.util.List;

/**
 * One kind of evidence that an email belongs to an agent (brief §4.1, §13). The router adds up the signals of every
 * strategy; a new strategy is a new {@code @ApplicationScoped} class implementing this interface, picked up without
 * changing the router.
 */
public interface RoutingStrategy {

    /** What the router knows about the email: never the display name, which the sender chooses freely. */
    record Input(String sender, boolean authenticated, SenderRole role, String subject, String body,
            String threadAgentId) {}

    /** One piece of evidence: how much it counts and a short reason the owner can read. */
    record Signal(String strategy, double weight, String reason) {}

    /** Short name for the decision log ("sender", "role", "keywords", "thread"). */
    String name();

    /** The evidence that {@code agent} fits this email; empty if none. */
    List<Signal> signals(AgentRegistry.Agent agent, Input email);
}
