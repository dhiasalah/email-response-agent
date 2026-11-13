package com.emailagent.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.emailagent.llm.LlmClient;
import com.emailagent.llm.LlmException;
import com.emailagent.llm.LlmOutputParser;
import com.emailagent.llm.LlmRequest;
import com.emailagent.mailbox.MailboxImporter;
import com.emailagent.mailbox.SenderDirectory;
import com.emailagent.model.EmailStatus;
import com.emailagent.model.SlotStatus;
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
 * The demo inbox through classification and routing, with a scripted model. On m10 the model is "fooled" (says LOW);
 * code still escalates. On m07 and m08 the detector stops the email before any model call.
 */
@QuarkusTest
class ClassificationFlowTest {

    @Inject MailboxImporter importer;
    @Inject Orchestrator orchestrator;
    @Inject InjectionDetector detector;
    @Inject SenderDirectory directory;
    @Inject LlmOutputParser parser;
    @Inject ResolverAgent resolver;
    @Inject ClassificationRouter router;

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

    /** The emails of the specialized agents' scenarios (brief §15): colleague, project, office, ambiguous. */
    static final Map<String, String> AGENT_ANSWERS = Map.of(
            "Jury du M2 IA", answer("QUESTION", "LOW", "FR", true,
                    q("Quelle est la date limite de saisie des notes ?", "DEADLINE", "grade submission", "")),
            "SuperMASProject: deliverable D2.1", answer("QUESTION", "LOW", "EN", true,
                    q("When is deliverable D2.1 due?", "DEADLINE", "supermasproject deliverable d2.1", "")),
            "Conventions de stage M2 IA", answer("QUESTION", "LOW", "FR", true,
                    q("Dans quel délai signez-vous les conventions ?", "PROCEDURE", "internship agreement signature", "")),
            "Attestation pour l'examen", answer("REQUEST", "LOW", "FR", true,
                    q("Pouvez-vous fournir une attestation ?", "INDIVIDUAL_DECISION", "attendance certificate", "")),
            "SuperMASProject deliverable", answer("QUESTION", "LOW", "EN", true,
                    q("When is deliverable D2.1 due?", "DEADLINE", "supermasproject deliverable d2.1", "")));

    @Test
    void demoInboxIsClassifiedAndRouted() {
        importer.importNew();
        ScriptedModel model = new ScriptedModel(parser);
        ClassifierAgent classifier = new ClassifierAgent(model, detector, directory, "scripted");

        orchestrator.process(List.of(classifier, router));

        assertThat(status("m01")).isEqualTo(EmailStatus.RESOLVING);
        assertThat(status("m02")).isEqualTo(EmailStatus.RESOLVING);
        assertThat(status("m03")).isEqualTo(EmailStatus.RESOLVING);
        assertThat(status("m12")).isEqualTo(EmailStatus.RESOLVING);
        assertThat(status("m07")).isEqualTo(EmailStatus.ESCALATED); // hidden HTML
        assertThat(status("m08")).isEqualTo(EmailStatus.ESCALATED); // visible injection + Unicode tricks
        assertThat(status("m09")).isEqualTo(EmailStatus.ESCALATED); // personal result
        assertThat(status("m10")).isEqualTo(EmailStatus.ESCALATED); // legal threat, despite the model's LOW
        assertThat(lastOutput("m10")).contains("sensitive_keyword");

        // the router also chose the specialized agent, with its reasons; escalated emails get one too, so the
        // owner sees who would have answered and a retry goes to that agent
        assertThat(lastOutput("m01")).contains("\"agent\":\"student\"").contains("sender role student");
        assertThat(lastOutput("m10")).contains("\"agent\":\"student\"");

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

        // the router chose a specialized agent for each email, from who wrote and what about (brief §4.1)
        assertThat(agentOf("m13")).isEqualTo("colleague");
        assertThat(agentOf("m14")).isEqualTo("supermas-project"); // same colleague, other context
        assertThat(agentOf("m15")).isEqualTo("administration");
        assertThat(agentOf("m16")).isEqualTo("student");
        assertThat(lastOutput("m16")).contains("\"ambiguous\":true", "\"runner_up\":\"administration\"");
        assertThat(agentOf("m17")).isEqualTo("student"); // names the project, but isn't a member

        // next stage: the resolver finds what it can, the rest waits for the owner
        orchestrator.process(List.of(resolver));

        // each agent answered from its own knowledge base; the student asking about the project got nothing from it
        assertThat(status("m13")).isEqualTo(EmailStatus.READY);
        assertThat(status("m14")).isEqualTo(EmailStatus.READY);
        assertThat(status("m15")).isEqualTo(EmailStatus.READY);
        assertThat(status("m17")).isEqualTo(EmailStatus.AWAITING_INPUT);
        QuarkusTransaction.requiringNew().run(() -> {
            assertThat(email("m13").slots).singleElement().extracting(s -> s.source).asString().startsWith("kb:col1");
            assertThat(email("m14").slots).singleElement().extracting(s -> s.source).asString().startsWith("kb:sp1");
            assertThat(email("m15").slots).singleElement().extracting(s -> s.source).asString().startsWith("kb:adm2");
            assertThat(email("m17").slots).singleElement().extracting(s -> s.status).isEqualTo(SlotStatus.UNRESOLVED);
        });
        for (String id : List.of("m01", "m02", "m03", "m12")) {
            assertThat(status(id)).as(id).isEqualTo(EmailStatus.AWAITING_INPUT);
        }
        QuarkusTransaction.requiringNew().run(() -> {
            assertThat(email("m01").slots).singleElement().satisfies(s -> {
                assertThat(s.status).isEqualTo(SlotStatus.PROPOSED); // calendar event, M2 IA
                assertThat(s.source).startsWith("calendar:ev1");
            });
            // Sarah's scope is unknown: the M2 IA calendar entries don't apply to her
            assertThat(email("m03").slots).allSatisfy(s -> assertThat(s.status).isEqualTo(SlotStatus.UNRESOLVED));
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
            String raw = ANSWERS.getOrDefault(subject, AGENT_ANSWERS.get(subject));
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

    private static String agentOf(String id) {
        return QuarkusTransaction.requiringNew().call(() -> email(id).agentId);
    }

    private static EmailEntity email(String id) {
        return EmailEntity.<EmailEntity>find("messageId like ?1", "<" + id + "@%").singleResult();
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
