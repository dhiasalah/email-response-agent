package com.emailagent.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.emailagent.llm.LlmClient;
import com.emailagent.llm.LlmException;
import com.emailagent.llm.LlmOutputParser;
import com.emailagent.llm.LlmRequest;
import com.emailagent.mailbox.MailboxImporter;
import com.emailagent.mailbox.SenderDirectory;
import com.emailagent.model.EmailStatus;
import com.emailagent.model.SlotType;
import com.emailagent.orchestrator.Orchestrator;
import com.emailagent.security.InjectionDetector;
import com.emailagent.store.DecisionLogEntry;
import com.emailagent.store.EmailEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The demo inbox through triage and routing, with a scripted model. On m10 the model is "fooled" (says LOW);
 * code still escalates. On m07 and m08 the detector stops the email before any model call.
 */
@QuarkusTest
class TriageFlowTest {

    @Inject MailboxImporter importer;
    @Inject Orchestrator orchestrator;
    @Inject InjectionDetector detector;
    @Inject SenderDirectory directory;
    @Inject LlmOutputParser parser;

    static final Map<String, String> ANSWERS = Map.of(
            "Notes du S2", answer("QUESTION", "LOW", "FR", true,
                    q("Quand les notes du S2 seront-elles publiées ?", "GRADES_PUBLICATION_DATE", "s2 exams", "")),
            "S2 grades", answer("QUESTION", "LOW", "EN", true,
                    q("When will the S2 grades be published?", "GRADES_PUBLICATION_DATE", "s2 exams", "")),
            "Résultats S2 ?", answer("QUESTION", "LOW", "FR", true,
                    q("Les résultats du S2 sont-ils disponibles ?", "GRADES_PUBLICATION_DATE", "s2 exams", ""),
                    q("Dans quelle salle a lieu le rattrapage ?", "EXAM_ROOM", "s2 exams", "retake")),
            "Ma note de Machine Learning", answer("QUESTION", "PERSONAL", "FR", true,
                    q("Quelle note ai-je eue ?", "INDIVIDUAL_RESULT", "machine learning exam", "")),
            // fooled model: a formal threat labelled as a harmless procedure question
            "Contestation de note", answer("QUESTION", "LOW", "FR", true,
                    q("Comment contester une note ?", "PROCEDURE", "grade appeal", "")),
            "Rendez-vous pour mon stage", answer("MEETING", "LOW", "FR", true,
                    q("Peut-on se rencontrer la semaine prochaine ?", "MEETING_AVAILABILITY", "internship", "")));

    @Test
    void demoInboxIsTriagedAndRouted() {
        importer.importNew();
        ScriptedModel model = new ScriptedModel(parser);
        TriageAgent triage = new TriageAgent(model, detector, directory, "scripted");

        orchestrator.process(List.of(triage, new TriageRouter()));

        assertThat(status("m01")).isEqualTo(EmailStatus.RESOLVING);
        assertThat(status("m02")).isEqualTo(EmailStatus.RESOLVING);
        assertThat(status("m03")).isEqualTo(EmailStatus.RESOLVING);
        assertThat(status("m12")).isEqualTo(EmailStatus.RESOLVING);
        assertThat(status("m07")).isEqualTo(EmailStatus.ESCALATED); // hidden HTML
        assertThat(status("m08")).isEqualTo(EmailStatus.ESCALATED); // visible injection + Unicode tricks
        assertThat(status("m09")).isEqualTo(EmailStatus.ESCALATED); // personal result
        assertThat(status("m10")).isEqualTo(EmailStatus.ESCALATED); // legal threat, despite the model's LOW
        assertThat(lastOutput("m10")).contains("sensitive_keyword");

        // the detector kept the two attacks away from the model entirely
        assertThat(model.subjects).doesNotContain("Deadline du projet", "Salle de l'examen");
        assertThat(lastOutput("m07")).contains("\"model_called\":false").contains("hidden_html");

        QuarkusTransaction.requiringNew().run(() -> {
            EmailEntity m03 = email("m03");
            assertThat(m03.slots).extracting(s -> s.type)
                    .containsExactlyInAnyOrder(SlotType.GRADES_PUBLICATION_DATE, SlotType.EXAM_ROOM);
            assertThat(m03.scope().isKnown()).isFalse(); // Sarah isn't in the roster: no memory reuse
            EmailEntity m01 = email("m01");
            assertThat(m01.scopeProgram).isEqualTo("M2");
            assertThat(m01.injectionScore).isZero();
            assertThat(email("m08").injectionSignals).contains("override_instructions", "bidi_control");
        });
    }

    // --- helpers ---

    /** Answers by subject, through the real strict parser, and remembers what it was shown. */
    static class ScriptedModel implements LlmClient {
        final LlmOutputParser parser;
        final List<String> subjects = new ArrayList<>();

        ScriptedModel(LlmOutputParser parser) {
            this.parser = parser;
        }

        @Override
        public <T> T call(LlmRequest request, Class<T> outputType) throws LlmException {
            String subject = request.untrustedInput().lines().findFirst().orElse("").replace("Subject: ", "");
            subjects.add(subject);
            String raw = ANSWERS.get(subject);
            if (raw == null) {
                throw new LlmException(LlmException.Reason.UNAVAILABLE, "no scripted answer");
            }
            return parser.parse(raw, outputType);
        }
    }

    static String q(String text, String type, String entity, String qualifier) {
        return "{\"text\":\"" + text + "\",\"slotType\":\"" + type + "\",\"entity\":\"" + entity
                + "\",\"qualifier\":\"" + qualifier + "\",\"owner\":\"USER\"}";
    }

    static String answer(String intent, String sensitivity, String language, boolean reply, String... questions) {
        return "{\"intent\":\"" + intent + "\",\"replyNeeded\":" + reply + ",\"sensitivity\":\"" + sensitivity
                + "\",\"language\":\"" + language + "\",\"questions\":[" + String.join(",", questions)
                + "],\"suspicious\":false}";
    }

    private static EmailEntity email(String id) {
        return EmailEntity.findByMessageId("<" + id + "@student.univ.example>").orElseThrow();
    }

    private static EmailStatus status(String id) {
        return QuarkusTransaction.requiringNew().call(() -> email(id).status);
    }

    private static String lastOutput(String id) {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<DecisionLogEntry> log = DecisionLogEntry.forEmail(email(id).id);
            return log.get(log.size() - 1).output;
        });
    }
}
