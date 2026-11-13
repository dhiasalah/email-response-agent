package com.emailagent.agents;

import com.emailagent.config.AppConfig;
import com.emailagent.store.AgentRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Chooses the specialized agent for an email (brief §4.1, §14 Q5–Q7). Plain code, no model: which context an email
 * may read is an access decision, so the email's text must not be able to argue with it. Every strategy adds
 * evidence; the agent with the most evidence wins.
 *
 * <ul>
 *   <li><b>Several agents suitable</b> (scores within {@code routing-tie-margin}): the one listed first in the
 *       agents' {@code order} wins, and the decision is flagged ambiguous with the runner-up named.</li>
 *   <li><b>No agent suitable</b> (best score under {@code routing-min-score}): the fallback agent takes the email.</li>
 *   <li><b>Members-only agents</b> are not candidates for a sender they don't list, whatever the email says.</li>
 * </ul>
 */
@ApplicationScoped
public class AgentRouter {

    /** The score of one agent and the reasons behind it. */
    public record Score(String agentId, double score, List<String> reasons) {}

    /**
     * The choice, with what the owner needs to trust or correct it: the confidence (0..1), whether another agent
     * was nearly as suitable, and every agent's score.
     */
    public record Decision(String agentId, double score, double confidence, boolean fallback, boolean ambiguous,
            String runnerUp, List<String> reasons, List<Score> scores) {

        /** For the decision log: ids, numbers and reasons written by code; no email text. */
        public String json() {
            String why = reasons.stream().map(r -> "\"" + r.replace("\"", "'") + "\"").collect(Collectors.joining(","));
            return "\"agent\":\"" + agentId + "\",\"score\":" + round(score) + ",\"confidence\":" + round(confidence)
                    + (fallback ? ",\"fallback\":true" : "") + (ambiguous ? ",\"ambiguous\":true" : "")
                    + (runnerUp == null ? "" : ",\"runner_up\":\"" + runnerUp + "\"") + ",\"why\":[" + why + "]";
        }
    }

    private final AgentRegistry registry;
    private final List<RoutingStrategy> strategies;
    private final double minScore;
    private final double tieMargin;

    @Inject
    public AgentRouter(AgentRegistry registry, Instance<RoutingStrategy> strategies, AppConfig config) {
        this(registry, strategies.stream().sorted(Comparator.comparing(RoutingStrategy::name)).toList(),
                config.routingMinScore(), config.routingTieMargin());
    }

    AgentRouter(AgentRegistry registry, List<RoutingStrategy> strategies, double minScore, double tieMargin) {
        this.registry = registry;
        this.strategies = List.copyOf(strategies);
        this.minScore = minScore;
        this.tieMargin = tieMargin;
    }

    public Decision route(RoutingStrategy.Input email) {
        List<Score> scores = new ArrayList<>();
        for (AgentRegistry.Agent agent : registry.all()) {
            if (agent.fallback() || !eligible(agent, email)) {
                continue;
            }
            double total = 0;
            List<String> reasons = new ArrayList<>();
            for (RoutingStrategy s : strategies) {
                for (RoutingStrategy.Signal signal : s.signals(agent, email)) {
                    total += signal.weight();
                    reasons.add(signal.reason());
                }
            }
            if (total > 0) {
                scores.add(new Score(agent.id(), total, reasons));
            }
        }
        // highest score first; registry order (the agents' "order") breaks exact ties
        List<String> order = registry.all().stream().map(AgentRegistry.Agent::id).toList();
        scores.sort(Comparator.comparingDouble(Score::score).reversed()
                .thenComparingInt(s -> order.indexOf(s.agentId())));

        AgentRegistry.Agent fallback = registry.fallback();
        if (scores.isEmpty() || scores.get(0).score() < minScore) {
            List<String> why = scores.isEmpty() ? List.of("no agent matched")
                    : List.of("best match " + scores.get(0).agentId() + " too weak (" + round(scores.get(0).score()) + ")");
            return new Decision(fallback.id(), 0, 0, true, false, scores.isEmpty() ? null : scores.get(0).agentId(),
                    why, List.copyOf(scores));
        }

        Score best = scores.get(0);
        // within the margin, the agent listed first wins: a stable, owner-controlled rule, not a coin toss
        // compared after rounding: 0.7 - 0.6 is 0.0999… in floating point, and must not count as a tie
        List<Score> close = scores.stream().filter(s -> round(best.score() - s.score()) < tieMargin).toList();
        Score chosen = close.stream().min(Comparator.comparingInt(s -> order.indexOf(s.agentId()))).orElse(best);
        Score runnerUp = scores.stream().filter(s -> s != chosen).findFirst().orElse(null);
        boolean ambiguous = close.size() > 1;
        double strength = Math.min(1.0, chosen.score());
        double share = runnerUp == null ? 1.0 : chosen.score() / (chosen.score() + runnerUp.score());
        return new Decision(chosen.agentId(), chosen.score(), strength * share, false, ambiguous,
                runnerUp == null ? null : runnerUp.agentId(), chosen.reasons(), List.copyOf(scores));
    }

    /** A members-only agent is a candidate only for an authenticated sender among its members. */
    static boolean eligible(AgentRegistry.Agent agent, RoutingStrategy.Input email) {
        if (!agent.routing().membersOnly()) {
            return true;
        }
        return email.authenticated() && email.sender() != null
                && agent.routing().members().contains(email.sender().toLowerCase(Locale.ROOT));
    }

    static double round(double d) {
        return Math.round(d * 100) / 100.0;
    }
}
