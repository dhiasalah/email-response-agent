package com.emailagent.security;

import jakarta.enterprise.context.ApplicationScoped;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Scores how much an email looks like it talks to the machine instead of the professor (OWASP LLM01).
 * Deterministic rules + the sanitizer's flags, run before any model sees the email. The model's own opinion can
 * only add to the score, never lower it: an attacker who controls the model can't use it to hide.
 */
@ApplicationScoped
public class InjectionDetector {

    /** At or above this score the email goes to a human and no agent works on it. */
    public static final double THRESHOLD = 1.0;

    public record Assessment(double score, List<String> signals) {

        public boolean suspicious() {
            return score >= THRESHOLD;
        }

        /** Adds the model's flag. Monotonic: a "not suspicious" from the model changes nothing. */
        public Assessment withModelFlag(boolean modelSaysSuspicious) {
            if (!modelSaysSuspicious || signals.contains("model_flag")) {
                return this;
            }
            List<String> more = new ArrayList<>(signals);
            more.add("model_flag");
            return new Assessment(score + MODEL_FLAG_WEIGHT, List.copyOf(more));
        }

        public String signalsCsv() {
            return String.join(",", signals);
        }
    }

    static final double MODEL_FLAG_WEIGHT = 0.6;

    // Text rules, matched on lower-case text without accents. Weights: 1.0 = enough alone, below = needs company.
    private static final Map<String, Rule> TEXT_RULES = new LinkedHashMap<>();

    private record Rule(Pattern pattern, double weight) {}

    static {
        // "ignore all previous instructions", "oubliez vos consignes" (but not "j'ai oublié les consignes du TP")
        text("override_instructions", 1.0,
                "\\b(ignor\\w*|disregard\\w*|forget|oubli\\w*|override|bypass)\\b[^.\\n]{0,40}"
                        + "\\b(previous|prior|above|earlier|preceding|all|any|your|system|precedente?s?"
                        + "|anterieure?s?|vos|tes|toutes?)\\b[^.\\n]{0,20}"
                        + "\\b(instructions?|rules?|prompts?|guidelines?|directives?|consignes?|regles?)\\b"
                        // French puts the adjective after the noun: "ignorez les instructions precedentes"
                        + "|\\b(ignor\\w*|disregard\\w*|forget|oubli\\w*)\\b[^.\\n]{0,20}"
                        + "\\b(instructions?|consignes?|regles?|rules?)\\s+(precedentes?|anterieures?|above|ci-dessus)\\b");
        // fake conversation roles and delimiters: "### SYSTEM", "assistant:", "</email>", "<|im_start|>"
        text("role_spoof", 1.0,
                "(^|\\n)\\s*(system|assistant|user|ai assistant|assistant ia)\\s*:"
                        + "|#{2,}\\s*(system|end|instructions?)"
                        + "|<\\|?\\s*im_(start|end)"
                        + "|</?\\s*(email|system|instructions?|prompt)\\s*>"
                        + "|\\[/?\\s*(inst|system)\\s*\\]"
                        + "|\\bend of (the )?(email|message)\\b");
        // speaking to the model rather than to the professor
        text("addresses_ai", 0.6,
                "\\b(ai|ia) (assistant|agent|model|system)\\b|\\bassistant (ia|ai|virtuel)\\b|\\blanguage model\\b"
                        + "|\\bmodele de langage\\b|\\bllm\\b|\\bchatgpt\\b|\\byou are now\\b|\\btu es maintenant\\b"
                        + "|\\bvous etes maintenant\\b|\\bas an ai\\b|\\bnew (instructions|task)\\b|\\bnouvelles? (instructions|taches?)\\b");
        // asks to send something to an address (exfiltration needs an address)
        text("exfiltration", 0.6,
                "\\b(forward|send|transfer|transferez|transfere|transferer|envoyez|envoie|envoyer)\\b"
                        + "[^\\n]{0,80}?[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
        // dictates the content of our reply: "reply that the exam is cancelled"
        text("dictates_reply", 0.3,
                "\\b(reply|respond|answer|say|repondez|reponds|repondre|dites|dis)\\s+(that|que|qu)\\b");
    }

    // Sanitizer flags (see Sanitizer): evidence of hiding something from the human reader
    private static final Map<String, Double> FLAG_WEIGHTS = Map.of(
            // students writing to a professor have no use for hidden text; mailing lists are already filtered out
            "hidden_html", 1.0,
            "bidi_control", 0.6,
            "invisible_unicode", 0.3,
            "mixed_scripts", 0.3,
            "subject_mixed_scripts", 0.3,
            "name_mixed_scripts", 0.6);

    private static void text(String name, double weight, String regex) {
        TEXT_RULES.put(name, new Rule(Pattern.compile(regex), weight));
    }

    public Assessment assess(String subject, String body, String sanitizerFlags) {
        String text = fold((subject == null ? "" : subject) + "\n" + (body == null ? "" : body));
        List<String> signals = new ArrayList<>();
        double score = 0;
        for (Map.Entry<String, Rule> rule : TEXT_RULES.entrySet()) {
            if (rule.getValue().pattern().matcher(text).find()) {
                signals.add(rule.getKey());
                score += rule.getValue().weight();
            }
        }
        Set<String> flags = sanitizerFlags == null || sanitizerFlags.isBlank()
                ? Set.of() : Set.of(sanitizerFlags.split(","));
        for (String flag : flags.stream().sorted().toList()) {
            Double weight = FLAG_WEIGHTS.get(flag);
            if (weight != null) {
                signals.add(flag);
                score += weight;
            }
        }
        return new Assessment(Math.round(score * 100) / 100.0, List.copyOf(signals));
    }

    /** Lower case, accents removed ("règles" → "regles"), so one pattern covers both spellings. */
    public static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
