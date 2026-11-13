package com.emailagent.agents;

import com.emailagent.security.InjectionDetector;
import com.emailagent.store.AgentRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The routing strategies shipped with the framework. Weights are on one scale: 1.0 is enough on its own, 0.3 is the
 * default minimum for an agent to be suitable (emailagent.routing-min-score). They are deliberately simple and
 * explainable: every point of an agent's score comes with a reason shown to the owner.
 */
public final class RoutingStrategies {

    private RoutingStrategies() {}

    /**
     * The sender's address or domain is listed by the agent. Only for an authenticated sender: the From header is
     * free text, so an unverified address proves nothing (design doc §8.1).
     */
    @ApplicationScoped
    public static class Sender implements RoutingStrategy {

        static final double ADDRESS = 1.0;
        static final double DOMAIN = 0.5;

        @Override
        public String name() {
            return "sender";
        }

        @Override
        public List<Signal> signals(AgentRegistry.Agent agent, Input email) {
            if (!email.authenticated() || email.sender() == null) {
                return List.of();
            }
            String sender = email.sender().toLowerCase(Locale.ROOT);
            if (agent.routing().senderAddresses().contains(sender)) {
                return List.of(new Signal(name(), ADDRESS, "sender listed by the agent"));
            }
            String domain = sender.substring(sender.indexOf('@') + 1);
            if (agent.routing().senderDomains().contains(domain)) {
                return List.of(new Signal(name(), DOMAIN, "sender domain " + domain));
            }
            return List.of();
        }
    }

    /** The sender's role in the roster (student, colleague, administration…) is one the agent serves. */
    @ApplicationScoped
    public static class Role implements RoutingStrategy {

        static final double WEIGHT = 0.4;

        @Override
        public String name() {
            return "role";
        }

        @Override
        public List<Signal> signals(AgentRegistry.Agent agent, Input email) {
            if (email.role() != null && agent.routing().senderRoles().contains(email.role())) {
                return List.of(new Signal(name(), WEIGHT, "sender role " + email.role().name().toLowerCase(Locale.ROOT)));
            }
            return List.of();
        }
    }

    /**
     * Words of the agent's list found in the subject or body, whole words, without case or accents. Each distinct
     * word counts, up to a cap: what the email is about can outweigh who sent it (brief §4.1: the same person may
     * write in different contexts), but words alone can't reach a members-only agent.
     */
    @ApplicationScoped
    public static class Keywords implements RoutingStrategy {

        static final double PER_WORD = 0.3;
        static final double CAP = 0.6;

        @Override
        public String name() {
            return "keywords";
        }

        @Override
        public List<Signal> signals(AgentRegistry.Agent agent, Input email) {
            String text = " " + InjectionDetector.fold((email.subject() == null ? "" : email.subject()) + " "
                    + (email.body() == null ? "" : email.body())) + " ";
            List<Signal> out = new ArrayList<>();
            double total = 0;
            for (String keyword : agent.routing().keywords()) {
                String k = InjectionDetector.fold(keyword);
                if (k.isBlank() || !Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(k) + "(?![\\p{L}\\p{N}])")
                        .matcher(text).find()) {
                    continue;
                }
                double w = Math.min(PER_WORD, CAP - total);
                if (w <= 0) {
                    break;
                }
                out.add(new Signal(name(), w, "mentions \"" + keyword + "\""));
                total += w;
            }
            return out;
        }
    }

    /** An earlier email of the same thread went to this agent: a conversation stays with one agent. */
    @ApplicationScoped
    public static class Thread implements RoutingStrategy {

        static final double WEIGHT = 0.8;

        @Override
        public String name() {
            return "thread";
        }

        @Override
        public List<Signal> signals(AgentRegistry.Agent agent, Input email) {
            if (agent.id().equals(email.threadAgentId())) {
                return List.of(new Signal(name(), WEIGHT, "same thread as an earlier email"));
            }
            return List.of();
        }
    }
}
