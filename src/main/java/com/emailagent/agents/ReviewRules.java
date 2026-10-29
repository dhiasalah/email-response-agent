package com.emailagent.agents;

import com.emailagent.model.Language;
import com.emailagent.orchestrator.EmailSnapshot;
import com.emailagent.security.InjectionDetector;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The reviewer's deterministic rules (design doc §9): plain code on the reply as it will be sent, run before any
 * model. A failure escalates the email; no model verdict can overrule it.
 */
final class ReviewRules {

    static final int MAX_WORDS = 200;

    private ReviewRules() {}

    /** The model's text between our fixed opening and closing, or null if the frame was changed. */
    static String content(String body, Language lang, String ownerName, boolean disclosure) {
        String opening = WriterAgent.opening(lang);
        String closing = WriterAgent.closing(lang, ownerName, disclosure);
        if (body == null || !body.startsWith(opening) || !body.endsWith(closing)
                || body.length() < opening.length() + closing.length()) {
            return null;
        }
        return body.substring(opening.length(), body.length() - closing.length());
    }

    /** Rule failures, in a fixed order; empty means every rule passed. */
    static List<String> failures(String content, EmailSnapshot email, List<WriterAgent.Fact> facts,
            InjectionDetector detector) {
        Set<String> failures = new LinkedHashSet<>();
        if (!WriterAgent.missing(facts, content).isEmpty()) {
            failures.add("fact_missing");
        }
        if (!unsupportedNumbers(content, facts, emailText(email)).isEmpty()) {
            failures.add("unsupported_number");
        }
        if (!unsupportedLinks(content, facts).isEmpty()) {
            failures.add("unsupported_link");
        }
        String folded = InjectionDetector.fold(content);
        if (TriageRouter.SENSITIVE_TOPICS.matcher(folded).find() || GRADE.matcher(content).find()) {
            failures.add("blocked_topic");
        }
        // a reply that itself reads like instructions to a machine carries the attacker's text to the next reader
        if (detector.assess(null, content, null).suspicious()) {
            failures.add("instructions_in_reply");
        }
        if (content.isBlank() || content.strip().split("\\s+").length > MAX_WORDS) {
            failures.add("length");
        }
        return List.copyOf(failures);
    }

    private static final Pattern NUMBER = Pattern.compile("\\d+");
    /** A letter (and an optional dash) right before the digits: "S2", "M1", "B204", "R-12"; not the "h" of "9h30". */
    private static final Pattern IDENTIFIER_PREFIX = Pattern.compile("(?<!\\d)\\p{L}-?$");

    /**
     * Numbers the reply states without a source. A date, hour or amount must come from a fact; an identifier
     * ("S2", "B204") may also echo the student's own words. Suffix letters don't make an identifier: "9h", "1er",
     * "18e" are times and dates, so they must come from a fact.
     */
    static List<String> unsupportedNumbers(String content, List<WriterAgent.Fact> facts, String emailText) {
        Set<String> fromFacts = new LinkedHashSet<>();
        facts.forEach(f -> fromFacts.addAll(numbers(f.display())));
        Set<String> fromEmail = numbers(emailText);
        List<String> unsupported = new ArrayList<>();
        Matcher m = NUMBER.matcher(content);
        while (m.find()) {
            String n = strip(m.group());
            if (fromFacts.contains(n)) {
                continue;
            }
            boolean identifier = IDENTIFIER_PREFIX.matcher(content.substring(Math.max(0, m.start() - 3), m.start()))
                    .find();
            if (identifier && fromEmail.contains(n)) {
                continue;
            }
            unsupported.add(m.group());
        }
        return unsupported;
    }

    /** Links, email addresses and domain names: the classic way to send a student somewhere else. */
    static final Pattern LINK = Pattern.compile("(?i)(?:https?://|www\\.)[^\\s<>\"]+"
            + "|[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+"
            + "|\\b(?:[a-z0-9-]+\\.)+(?:com|org|net|edu|gov|fr|eu|io|co|me|ly|info|biz|xyz|app|link|ru|cn|example)\\b"
            + "(?:/[^\\s<>\"]*)?");

    /** A link is allowed only if a fact contains it word for word (a course page from the knowledge base). */
    static List<String> unsupportedLinks(String content, List<WriterAgent.Fact> facts) {
        String factText = facts.stream().map(WriterAgent.Fact::display).reduce("", (a, b) -> a + "\n" + b)
                .toLowerCase(Locale.ROOT);
        List<String> unsupported = new ArrayList<>();
        Matcher m = LINK.matcher(content);
        while (m.find()) {
            String link = m.group().replaceAll("[.,;:!?)\\]]+$", "");
            if (!factText.contains(link.toLowerCase(Locale.ROOT))) {
                unsupported.add(link);
            }
        }
        return unsupported;
    }

    /** "14/20", "12,5 / 20": an individual grade never goes out in an automatic reply (design doc §9). */
    static final Pattern GRADE = Pattern.compile("\\b\\d{1,2}(?:[.,]\\d{1,2})?\\s*/\\s*20\\b");

    /** Reasons to show this reply on its own and never send it in bulk or automatically (design doc §7.6). */
    static List<String> outliers(int attempts, List<WriterAgent.Fact> facts, String sanitizerFlags,
            String injectionSignals) {
        List<String> outliers = new ArrayList<>();
        if (attempts > 1) {
            outliers.add("writer_retry");
        }
        // a bare yes/no has no anchor code can check: only the model vouched for it
        if (facts.stream().anyMatch(f -> f.display().strip().toLowerCase(Locale.ROOT).matches("yes|no|oui|non"))) {
            outliers.add("unanchored_fact");
        }
        if (notBlank(sanitizerFlags) || notBlank(injectionSignals)) {
            outliers.add("suspicious_input");
        }
        return outliers;
    }

    private static String emailText(EmailSnapshot email) {
        return (email.subject() == null ? "" : email.subject()) + "\n" + (email.body() == null ? "" : email.body());
    }

    private static Set<String> numbers(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = NUMBER.matcher(text == null ? "" : text);
        while (m.find()) {
            out.add(strip(m.group()));
        }
        return out;
    }

    private static String strip(String digits) {
        return digits.replaceFirst("^0+(?=\\d)", "");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
