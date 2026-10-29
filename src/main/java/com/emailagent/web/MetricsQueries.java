package com.emailagent.web;

import com.emailagent.model.EmailStatus;
import com.emailagent.model.SlotStatus;
import com.emailagent.model.SlotType;
import com.emailagent.store.DecisionLogEntry;
import com.emailagent.store.EmailEntity;
import com.emailagent.store.ReviewEntity;
import com.emailagent.store.SlotEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Production metrics from the shared state (design doc §12), per slot type where it matters. Read-only; the numbers
 * inform the owner's decision to enable auto-send for a type, they never enable it themselves.
 */
@ApplicationScoped
public class MetricsQueries {

    /** Design doc §12: acceptance above 90% over 4 weeks; and enough decisions for the rate to mean something. */
    static final double ACCEPTANCE_TARGET = 0.90;
    static final int MIN_DECISIONS = 20;
    static final Duration WINDOW = Duration.ofDays(28);

    public record Readiness(SlotType type, int accepted, int rejected, String rate, boolean ready, String why) {}

    public record Metrics(Map<String, Long> byStatus, Map<String, Long> filledFrom, String memoryReuse,
            Map<String, Long> escalations, long reviewsPassed, long reviewsFailed, Map<String, Long> ruleFailures,
            Map<String, Long> modelIssues, List<Readiness> readiness) {}

    public Metrics metrics() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Map<String, Long> byStatus = new LinkedHashMap<>();
            for (EmailStatus s : EmailStatus.values()) {
                long n = EmailEntity.count("status", s);
                if (n > 0) {
                    byStatus.put(s.name(), n);
                }
            }
            Map<String, Long> filledFrom = new TreeMap<>();
            for (SlotEntity s : SlotEntity.<SlotEntity>list("status", SlotStatus.FILLED)) {
                filledFrom.merge(origin(s.source), 1L, Long::sum);
            }
            Map<String, Long> escalations = new TreeMap<>();
            for (DecisionLogEntry d : DecisionLogEntry.<DecisionLogEntry>list("toStatus", EmailStatus.ESCALATED)) {
                escalations.merge(d.agent + ": " + code(d.output), 1L, Long::sum);
            }
            Map<String, Long> rules = new TreeMap<>();
            Map<String, Long> issues = new TreeMap<>();
            long passed = 0;
            long failed = 0;
            for (ReviewEntity r : ReviewEntity.<ReviewEntity>listAll()) {
                if (r.passed) {
                    passed++;
                } else {
                    failed++;
                }
                split(r.ruleFailures).forEach(c -> rules.merge(c, 1L, Long::sum));
                split(r.modelIssues).forEach(c -> issues.merge(c, 1L, Long::sum));
            }
            return new Metrics(byStatus, filledFrom, memoryReuse(filledFrom), escalations, passed, failed, rules,
                    issues, readiness());
        });
    }

    /** Share of filled slots answered without asking the owner: memory, knowledge base, recent answers. */
    static String memoryReuse(Map<String, Long> filledFrom) {
        long total = filledFrom.values().stream().mapToLong(Long::longValue).sum();
        long reused = filledFrom.entrySet().stream().filter(e -> !e.getKey().equals("card")).mapToLong(Map.Entry::getValue)
                .sum();
        return total == 0 ? "n/a" : String.format("%.0f%% (%d/%d)", 100.0 * reused / total, reused, total);
    }

    /** Owner decisions on saved drafts over the window, per slot type: the evidence for enabling auto-send. */
    private static List<Readiness> readiness() {
        Map<SlotType, int[]> counts = new EnumMap<>(SlotType.class);
        Instant since = Instant.now().minus(WINDOW);
        for (DecisionLogEntry d : DecisionLogEntry.<DecisionLogEntry>list(
                "agent = 'owner' and fromStatus = ?1 and createdAt > ?2", EmailStatus.DRAFT_SAVED, since)) {
            boolean accepted = d.toStatus == EmailStatus.SENT;
            SlotEntity.<SlotEntity>list("email.id", d.emailId).stream().map(s -> s.type).distinct()
                    .forEach(t -> counts.computeIfAbsent(t, k -> new int[2])[accepted ? 0 : 1]++);
        }
        List<Readiness> out = new ArrayList<>();
        Arrays.stream(SlotType.values()).filter(SlotType::shareable)
                .forEach(t -> out.add(readiness(t, counts.getOrDefault(t, new int[2]))));
        return out;
    }

    static Readiness readiness(SlotType type, int[] c) {
        int decisions = c[0] + c[1];
        String rate = decisions == 0 ? "n/a" : String.format("%.0f%%", 100.0 * c[0] / decisions);
        String why;
        boolean ready = false;
        if (decisions < MIN_DECISIONS) {
            why = "needs " + MIN_DECISIONS + " owner decisions in 4 weeks (" + decisions + " so far)";
        } else if ((double) c[0] / decisions < ACCEPTANCE_TARGET) {
            why = "acceptance below 90%";
        } else {
            ready = true;
            why = "meets the doc's target; enabling stays the owner's choice (auto-send-types)";
        }
        return new Readiness(type, c[0], c[1], rate, ready, why);
    }

    /** "fact:3:v1" → "fact", "card:7 answered by owner" → "card", "kb:kb2 …" → "kb". */
    static String origin(String source) {
        if (source == null || source.isBlank()) {
            return "unknown";
        }
        String head = source.split("[: ]", 2)[0];
        return head.isBlank() ? "unknown" : head;
    }

    private static final Pattern CODE = Pattern.compile("\"(escalated|route|rules)\":\\[?\"([a-z_]+)\"");

    /** Our own reason code from an audit row's output ({"escalated":"x"}, {"route":"y"}…), never free text. */
    static String code(String output) {
        if (output == null) {
            return "?";
        }
        Matcher m = CODE.matcher(output);
        if (m.find()) {
            return m.group(2);
        }
        return output.contains("\"model_called\":false") ? "injection_detector" : "other";
    }

    private static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of() : List.of(csv.split(","));
    }
}
