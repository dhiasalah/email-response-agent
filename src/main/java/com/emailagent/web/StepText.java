package com.emailagent.web;

import com.emailagent.model.EmailStatus;
import com.emailagent.store.DecisionLogEntry;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * One audit row as a line of an email's history ("Reviewer · Draft passed review"). Built from our own fields only
 * (agent name, statuses, our JSON codes), never from a model's words, like {@link ReasonText}.
 */
public final class StepText {

    /** {@code tone} picks the dot colour: ok, warn, danger, owner or neutral. */
    public record Step(String when, String actor, String text, String tone) {}

    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault());

    static final Map<String, String> ACTORS = Map.ofEntries(
            Map.entry("prefilter", "Pre-filter"),
            Map.entry("classifier", "Classifier"),
            Map.entry("router", "Router"),
            Map.entry("resolver", "Resolver"),
            Map.entry("grouping", "Grouping"),
            Map.entry("writer", "Writer"),
            Map.entry("reviewer", "Reviewer"),
            Map.entry("dispatcher", "Send policy"),
            Map.entry("curator", "Curator"),
            Map.entry("outbox", "Mailbox"),
            Map.entry("clarification", "Clarification"),
            Map.entry("holding", "Holding reply"),
            Map.entry("broadcast", "Broadcast"),
            Map.entry("erasure", "Retention"),
            Map.entry("owner", "You"));

    private StepText() {}

    public static Step of(DecisionLogEntry d) {
        return of(d.agent, d.fromStatus, d.toStatus, d.output, d.createdAt);
    }

    static Step of(String agent, EmailStatus from, EmailStatus to, String output, Instant at) {
        Step step = base(agent, from, to, output, at);
        if ("router".equals(agent) && output != null && output.contains("\"agent\":\"")) {
            return new Step(step.when(), step.actor(), step.text() + " · " + routing(output), step.tone());
        }
        return step;
    }

    /**
     * The routing decision in words: which agent, how sure, the runner-up when it was close, and the reasons. All of
     * it is ours: agent ids and reasons written by the router's code (keywords come from the owner's agent files).
     */
    static String routing(String out) {
        String agent = raw(out, "agent");
        String runnerUp = raw(out, "runner_up");
        StringBuilder text = new StringBuilder("handled by the ").append(agent).append(" agent");
        if (out.contains("\"fallback\":true")) {
            text.append(" (no specialized agent fitted)");
        } else {
            text.append(" (confidence ").append(number(out, "confidence")).append(")");
            if (out.contains("\"ambiguous\":true") && !runnerUp.isEmpty()) {
                text.append(", close call with ").append(runnerUp);
            }
        }
        int i = out.indexOf("\"why\":[");
        int end = i < 0 ? -1 : out.indexOf(']', i);
        if (end > i) {
            String why = out.substring(i + 7, end).replace("\",\"", "; ").replace("\"", "").strip();
            if (!why.isEmpty()) {
                text.append(": ").append(why);
            }
        }
        return text.toString();
    }

    private static Step base(String agent, EmailStatus from, EmailStatus to, String output, Instant at) {
        String actor = ACTORS.getOrDefault(agent, agent == null ? "System" : ReasonText.words(agent));
        String when = at == null ? "" : WHEN.format(at);
        String out = output == null ? "" : output;
        if (to == null || to == from) {
            return new Step(when, actor, note(agent, out), "neutral");
        }
        return switch (to) {
            case FILTERED_OUT -> new Step(when, actor, "Filtered out before any model read it", "neutral");
            case CLASSIFIED -> new Step(when, actor, from == EmailStatus.AWAITING_SENDER
                    ? "The sender answered: classified again" : "Classified and split into questions", "neutral");
            case IGNORED -> new Step(when, actor, "No reply needed", "neutral");
            case RESOLVING -> new Step(when, actor, "Routed to the resolver", "neutral");
            case AWAITING_SENDER -> new Step(when, actor, "Asked the sender for missing details", "warn");
            case AWAITING_INPUT -> new Step(when, actor, "Waiting for your answer on a card", "warn");
            case READY -> new Step(when, actor, from == EmailStatus.ESCALATED ? "You asked the agent to rewrite it"
                    : from == EmailStatus.AWAITING_INPUT ? "Your card answer filled every slot"
                    : "Every answer found", agent != null && agent.equals("owner") ? "owner" : "ok");
            case DRAFTED -> new Step(when, actor, "Draft written", "neutral");
            case REVIEWED -> new Step(when, actor, "Draft passed review", "ok");
            case DRAFT_SAVED -> new Step(when, actor, "Saved for your approval", "ok");
            case ESCALATED -> "owner".equals(agent) ? new Step(when, actor, "You rejected the draft", "owner")
                    : new Step(when, actor, "Handed to you: " + lower(ReasonText.of(agent, out).title()), "danger");
            case SENT -> new Step(when, actor, sent(agent, out), "owner".equals(agent) ? "owner" : "ok");
            case CLOSED -> new Step(when, actor, "owner".equals(agent) ? "You closed it" : "Closed", "neutral");
            default -> new Step(when, actor, ReasonText.words(to.name()), "neutral");
        };
    }

    private static String sent(String agent, String out) {
        if (!"owner".equals(agent)) {
            return "Sent automatically after review";
        }
        if (out.contains("\"edited\":true")) {
            return "You sent your own version";
        }
        return "You approved and sent the draft";
    }

    /** Rows that don't move the email: what the agent did on the side. */
    private static String note(String agent, String out) {
        if (agent == null) {
            return "Note";
        }
        return switch (agent) {
            case "prefilter" -> out.contains("\"filtered\":\"") ? "Filtered out: " + code(out, "filtered")
                    : out.contains("\"flags\":\"\"") || !out.contains("\"flags\"") ? "Checked before any model call: clean"
                    : "Checked before any model call: flagged " + code(out, "flags").replace(",", ", ");
            case "grouping" -> out.contains("\"by\":\"NEW\"") ? "Put on a new card"
                    : "Joined a card waiting on the same answer";
            case "curator" -> out.contains("\"learned\":0") ? "Checked your sent replies: nothing new to learn"
                    : out.contains("\"learned\"") ? "Learned an answer from your reply" : "Your answer was remembered";
            case "outbox" -> out.contains("\"delivered\":\"draft\"") ? "Saved in your mailbox drafts"
                    : "Delivered to the mailbox";
            case "clarification" -> out.contains("\"mode\":\"draft\"") ? "Question to the sender saved as a draft"
                    : "Question sent to the sender";
            case "holding" -> "Holding reply prepared";
            case "owner" -> out.contains("forgot_fact") ? "You made the agent forget a fact" : "Action recorded";
            default -> "Step recorded";
        };
    }

    /** The string value of a top-level field of our JSON, in words ("AUTO_REPLY" → "auto reply"). */
    static String code(String json, String field) {
        String key = "\"" + field + "\":\"";
        int i = json.indexOf(key);
        if (i < 0) {
            return "";
        }
        int start = i + key.length();
        int end = json.indexOf('"', start);
        return end < 0 ? "" : ReasonText.words(json.substring(start, end));
    }

    /** A top-level string field of our JSON, as written ("supermas-project"); empty if absent. */
    static String raw(String json, String field) {
        String key = "\"" + field + "\":\"";
        int i = json.indexOf(key);
        if (i < 0) {
            return "";
        }
        int start = i + key.length();
        int end = json.indexOf('"', start);
        return end < 0 ? "" : json.substring(start, end);
    }

    /** A top-level number field of our JSON, as written; empty if absent. */
    static String number(String json, String field) {
        String key = "\"" + field + "\":";
        int i = json.indexOf(key);
        if (i < 0) {
            return "";
        }
        int start = i + key.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.')) {
            end++;
        }
        return json.substring(start, end);
    }

    /** "Possible prompt injection: …" reads better mid-sentence in lower case. */
    private static String lower(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
