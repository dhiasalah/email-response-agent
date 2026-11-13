package com.emailagent.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.emailagent.llm.LlmClient;
import com.emailagent.llm.LlmRequest;
import com.emailagent.llm.ModelTier;
import com.emailagent.model.EmailStatus;
import com.emailagent.model.Language;
import com.emailagent.model.SenderScope;
import com.emailagent.model.SlotOwner;
import com.emailagent.model.SlotStatus;
import com.emailagent.model.SlotType;
import com.emailagent.step.EmailSnapshot;
import com.emailagent.security.InjectionDetector;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The evaluation set (design doc §12): labelled emails through the real triage model and the router, labelled drafts
 * through the real reviewer. Prints and writes target/evaluation-report.md. The only hard assertion is the
 * safety one: nothing that must reach the owner is routed to an automatic answer.
 */
@QuarkusTest
@Tag("ollama")
class EvaluationOllamaTest {

    record Labelled(String id, String lang, String subject, String body, String intent, List<SlotType> slots,
            String route, String note) {}

    record LabelledReview(String id, String lang, String email, String content, boolean pass, String note) {}

    @Inject LlmClient llm;
    @Inject InjectionDetector detector;
    @Inject ReviewerAgent reviewer;
    @Inject ObjectMapper mapper;

    @Test
    void triageAndRoutingOnTheEvaluationSet() throws Exception {
        List<Labelled> set = read("/eval/emails.json", new TypeReference<>() {});
        int intentOk = 0;
        Map<SlotType, int[]> perType = new EnumMap<>(SlotType.class); // tp, fp, fn
        Map<String, Map<String, Integer>> confusion = new TreeMap<>();
        List<String> unsafe = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();

        for (Labelled l : set) {
            String route;
            String intent = "-";
            List<SlotType> got = List.of();
            InjectionDetector.Assessment a = detector.assess(l.subject(), l.body(), null);
            if (a.suspicious()) {
                route = "ESCALATE";
                intent = "(detector)";
            } else {
                long start = System.nanoTime();
                TriageOutput out = llm.call(new LlmRequest("triage", ModelTier.FAST, TriageAgent.INSTRUCTIONS,
                        "Subject: " + l.subject() + "\n\n" + l.body()), TriageOutput.class);
                latencies.add((System.nanoTime() - start) / 1_000_000);
                if (a.withModelFlag(out.suspicious()).suspicious()) {
                    route = "ESCALATE";
                } else {
                    route = switch (TriageRouter.route(snapshot(out)).next()) {
                        case ESCALATED -> "ESCALATE";
                        case IGNORED -> "IGNORE";
                        default -> "ANSWER";
                    };
                }
                intent = out.intent().name();
                got = out.questions().stream().map(TriageOutput.Question::slotType).distinct().toList();
            }
            if (intent.equals(l.intent())) {
                intentOk++;
            }
            if (!intent.equals("(detector)")) {
                for (SlotType t : SlotType.values()) {
                    boolean exp = l.slots().contains(t);
                    boolean pred = got.contains(t);
                    int[] c = perType.computeIfAbsent(t, k -> new int[3]);
                    if (exp && pred) c[0]++;
                    if (!exp && pred) c[1]++;
                    if (exp && !pred) c[2]++;
                }
            }
            confusion.computeIfAbsent(l.route(), k -> new TreeMap<>()).merge(route, 1, Integer::sum);
            if (l.route().equals("ESCALATE") && !route.equals("ESCALATE")) {
                unsafe.add(l.id());
            }
            rows.add("| " + l.id() + " | " + l.route() + " | " + route + (route.equals(l.route()) ? "" : " ✗")
                    + " | " + l.intent() + " | " + intent + " | " + l.slots() + " | " + got + " |");
        }

        StringBuilder md = new StringBuilder("## Triage and routing (" + set.size() + " labelled emails, model "
                + "qwen2.5)\n\n");
        long routeOk = confusion.entrySet().stream().mapToLong(e -> e.getValue().getOrDefault(e.getKey(), 0)).sum();
        md.append("- Routing accuracy: ").append(pct(routeOk, set.size())).append('\n');
        md.append("- Intent accuracy: ").append(pct(intentOk, set.size())).append('\n');
        md.append("- **Unsafe misses** (must reach the owner, routed elsewhere): ").append(unsafe.size())
                .append(unsafe.isEmpty() ? "" : " " + unsafe).append('\n');
        latencies.sort(Long::compare);
        if (!latencies.isEmpty()) {
            md.append("- Triage latency: median ").append(latencies.get(latencies.size() / 2)).append(" ms, max ")
                    .append(latencies.get(latencies.size() - 1)).append(" ms\n");
        }
        md.append("\n| Expected \\ Routed | ANSWER | ESCALATE | IGNORE |\n|---|---|---|---|\n");
        for (String exp : List.of("ANSWER", "ESCALATE", "IGNORE")) {
            Map<String, Integer> r = confusion.getOrDefault(exp, Map.of());
            md.append("| ").append(exp).append(" | ").append(r.getOrDefault("ANSWER", 0)).append(" | ")
                    .append(r.getOrDefault("ESCALATE", 0)).append(" | ").append(r.getOrDefault("IGNORE", 0))
                    .append(" |\n");
        }
        md.append("\n| Slot type | Precision | Recall | Support |\n|---|---|---|---|\n");
        perType.forEach((t, c) -> {
            if (c[0] + c[1] + c[2] > 0) {
                md.append("| ").append(t).append(" | ").append(pct(c[0], c[0] + c[1])).append(" | ")
                        .append(pct(c[0], c[0] + c[2])).append(" | ").append(c[0] + c[2]).append(" |\n");
            }
        });
        md.append("\n<details><summary>Per email</summary>\n\n| Id | Expected route | Routed | Expected intent | "
                + "Intent | Expected slots | Slots |\n|---|---|---|---|---|---|---|\n")
                .append(String.join("\n", rows)).append("\n\n</details>\n");
        write("evaluation-triage.md", md.toString());

        assertThat(unsafe).as("emails that must reach the owner but were routed to an automatic answer").isEmpty();
    }

    @Test
    void reviewerOnLabelledDrafts() throws Exception {
        List<LabelledReview> set = read("/eval/reviews.json", new TypeReference<>() {});
        int truePass = 0, falsePass = 0, trueFail = 0, falseFail = 0, byRules = 0;
        List<String> rows = new ArrayList<>();
        for (LabelledReview l : set) {
            Language lang = Language.valueOf(l.lang());
            // the draft is written in English for F4 (wrong language): the reviewer sees the email's language
            Language draftLang = l.id().equals("F4") ? Language.FR : lang;
            EmailSnapshot email = new EmailSnapshot(1, 0, EmailStatus.DRAFTED, "<x@test>", null, "s@univ.example",
                    "S", "Exam", l.email(), Instant.now(), SenderScope.UNKNOWN, "student", null, null, lang, true, null,
                    List.of(new EmailSnapshot.Slot(1, SlotType.EXAM_DATE, "ml exam", null, null, SlotOwner.USER,
                            SlotStatus.FILLED, "2027-06-03", "fact:2:v1")));
            String body = WriterAgent.opening(draftLang) + l.content()
                    + WriterAgent.closing(draftLang, "Prof. Martin", true);
            ReviewerAgent.Review r = reviewer.review(email, new ReviewerAgent.Reply(1, body, draftLang, 1), null);
            if (!r.modelCalled()) {
                byRules++;
            }
            if (l.pass() && r.passed()) truePass++;
            if (!l.pass() && r.passed()) falsePass++;
            if (!l.pass() && !r.passed()) trueFail++;
            if (l.pass() && !r.passed()) falseFail++;
            rows.add("| " + l.id() + " | " + l.note() + " | " + (l.pass() ? "pass" : "fail") + " | "
                    + (r.passed() ? "pass" : "fail") + (r.passed() == l.pass() ? "" : " ✗") + " | "
                    + (r.modelCalled() ? r.modelIssues() : "rules " + r.ruleFailures()) + " |");
        }
        String md = "## Reviewer (" + set.size() + " labelled drafts that pass the code rules by design)\n\n"
                + "- Catch rate (bad drafts failed): " + pct(trueFail, trueFail + falsePass) + "\n"
                + "- False alarms (good drafts failed): " + pct(falseFail, truePass + falseFail) + "\n"
                + "- Decided by code rules before the model: " + byRules + "\n\n"
                + "| Id | Case | Expected | Reviewer | Why |\n|---|---|---|---|---|\n" + String.join("\n", rows) + "\n";
        write("evaluation-reviewer.md", md);
    }

    private static EmailSnapshot snapshot(TriageOutput out) {
        List<EmailSnapshot.Slot> slots = new ArrayList<>();
        long n = 1;
        for (TriageOutput.Question q : out.questions()) {
            String entity = q.entity().strip().toLowerCase();
            slots.add(new EmailSnapshot.Slot(n++, q.slotType(), entity, null, q.text(),
                    TriageAgent.owner(entity, q.owner()), SlotStatus.UNRESOLVED, null, null));
        }
        return new EmailSnapshot(1, 0, EmailStatus.TRIAGED, "<x>", null, "s@univ.example", null, "", "",
                Instant.now(), SenderScope.UNKNOWN, "student", out.intent(), out.sensitivity(), out.language(), out.replyNeeded(),
                null, slots);
    }

    private <T> T read(String resource, TypeReference<T> type) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            return mapper.readValue(in, type);
        }
    }

    private static String pct(long a, long b) {
        return b == 0 ? "n/a" : String.format("%.0f%% (%d/%d)", 100.0 * a / b, a, b);
    }

    private static void write(String name, String md) throws Exception {
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", name), md);
        System.out.println(md);
    }
}
