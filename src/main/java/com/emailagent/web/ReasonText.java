package com.emailagent.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns an escalation's audit row ({@code agent} + our JSON output) into sentences the owner can act on, instead of
 * {@code {"model_called":false,"injection":{...}}}. Input is always our own codes (the audit never stores a model's
 * words), and an unknown code is shown as words, never dropped: a new reason must not become an empty line.
 */
public final class ReasonText {

    /** {@code kind} picks the badge colour: security, policy, review, owner or error. */
    public record Reason(String title, List<String> details, String kind) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    static final Map<String, String> SIGNALS = Map.ofEntries(
            Map.entry("hidden_html", "hidden text in the HTML"),
            Map.entry("invisible_unicode", "invisible characters"),
            Map.entry("bidi_control", "text-direction tricks"),
            Map.entry("override_instructions", "tries to override the agent's instructions"),
            Map.entry("dictates_reply", "tells the agent what to reply"),
            Map.entry("addresses_ai", "speaks to the AI directly"),
            Map.entry("exfiltration", "asks to send information elsewhere"),
            Map.entry("role_spoof", "pretends to be staff or the system"),
            Map.entry("mixed_scripts", "look-alike letters from mixed alphabets in the text"),
            Map.entry("name_mixed_scripts", "sender name uses look-alike letters (possible impersonation)"),
            Map.entry("subject_mixed_scripts", "subject uses look-alike letters"),
            Map.entry("model_flag", "the triage model found it suspicious"));

    static final Map<String, String> ROUTES = Map.of(
            "sensitive_keyword", "Sensitive topic (dispute, legal, health…): you answer it yourself",
            "complaint", "Complaint: you answer it yourself",
            "not_automatable", "Personal result (such as a grade): never answered automatically",
            "no_registered_slot", "A question of a kind the agent doesn't handle yet");

    static final Map<String, String> RULES = Map.of(
            "fact_missing", "a fact from the answer is missing",
            "unsupported_number", "contains a number no source supports",
            "unsupported_link", "contains a link no source supports",
            "blocked_topic", "touches a blocked topic",
            "unsupported_commitment", "makes a promise you didn't make",
            "instructions_in_reply", "reads like instructions to a machine",
            "language_mismatch", "is not in the sender's language",
            "length", "is too long");

    static final Map<String, String> ISSUES = Map.of(
            "UNSUPPORTED_CLAIM", "states something no source backs",
            "NEW_COMMITMENT", "promises something you didn't decide",
            "FOLLOWS_EMAIL_INSTRUCTION", "follows instructions written in the email",
            "LEAKS_INFORMATION", "may disclose information it shouldn't",
            "WRONG_LANGUAGE", "is in the wrong language",
            "IMPOLITE", "has a tone judged impolite");

    static final Map<String, String> ESCALATED = Map.ofEntries(
            Map.entry("review_mismatch", "The draft changed after its review"),
            Map.entry("no_current_draft", "No draft to review"),
            Map.entry("slots_not_filled", "Some answers are still missing"),
            Map.entry("not_automatable", "Personal result (such as a grade): never answered automatically"),
            Map.entry("writer_incomplete", "The writer left out a fact twice"),
            Map.entry("clarification_rounds_exhausted", "Still missing details after two questions to the sender"),
            Map.entry("nothing_to_ask", "The sender's details are missing and there is nothing the agent may ask"),
            Map.entry("erasure_request", "Erasure request"),
            Map.entry("step_error", "An internal error stopped the agent"),
            Map.entry("write_error", "An internal error stopped the agent"),
            Map.entry("illegal_transition", "An agent's result was refused by the safety checks"));

    private ReasonText() {}

    public static Reason of(String agent, String output) {
        JsonNode n = parse(output);
        if (n == null) {
            return new Reason(agent == null ? "Escalated" : "Escalated by " + agent, List.of(), "error");
        }
        if (n.has("injection") && n.path("injection").path("signals").asText("").length() > 0) {
            List<String> details = new ArrayList<>();
            for (String s : n.path("injection").path("signals").asText().split(",")) {
                if (!s.isBlank()) {
                    details.add(SIGNALS.getOrDefault(s, words(s)));
                }
            }
            return new Reason("Possible prompt injection: the agent did not process it", details, "security");
        }
        if (n.has("route")) {
            String route = n.get("route").asText();
            String text = ROUTES.get(route);
            if (text == null && route.startsWith("sensitive_")) {
                text = "Marked sensitive (" + words(route.substring("sensitive_".length())) + "): you answer it yourself";
            }
            return new Reason(text == null ? words(route) : text, List.of(), "policy");
        }
        if (n.has("passed") && !n.get("passed").asBoolean()) {
            List<String> details = new ArrayList<>();
            n.path("rules").forEach(r -> details.add("The reply " + RULES.getOrDefault(r.asText(), words(r.asText()))));
            n.path("issues").forEach(i -> details.add("The reply " + ISSUES.getOrDefault(i.asText(), words(i.asText()))));
            return new Reason("The reviewer stopped the draft", details, "review");
        }
        if (n.has("rejected_by")) {
            return new Reason("You rejected the draft", List.of(), "owner");
        }
        if (n.has("escalated")) {
            String code = n.get("escalated").asText();
            if (code.startsWith("llm_")) {
                return new Reason("The local model failed (" + words(code.substring(4)) + ")", List.of(), "error");
            }
            return new Reason(ESCALATED.getOrDefault(code, words(code)), List.of(),
                    code.endsWith("_error") || code.equals("illegal_transition") ? "error" : "policy");
        }
        return new Reason(agent == null ? "Escalated" : "Escalated by " + agent, List.of(), "error");
    }

    private static JsonNode parse(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(output);
            return n != null && n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** "rate_limit_sender" → "rate limit sender": a code we don't know yet, still readable. */
    static String words(String code) {
        return code.replace('_', ' ').toLowerCase(Locale.ROOT).strip();
    }
}
