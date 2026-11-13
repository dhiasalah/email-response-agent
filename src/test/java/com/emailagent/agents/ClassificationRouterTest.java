package com.emailagent.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.emailagent.model.EmailStatus;
import com.emailagent.model.Intent;
import com.emailagent.model.Language;
import com.emailagent.model.SenderScope;
import com.emailagent.model.Sensitivity;
import com.emailagent.model.SlotOwner;
import com.emailagent.model.SlotStatus;
import com.emailagent.model.SlotType;
import com.emailagent.step.EmailSnapshot;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Policy in code: what the classifier's labels and the taxonomy flags imply, whatever the model wanted. */
class ClassificationRouterTest {

    @Test
    void ordinaryQuestionGoesToTheResolver() {
        assertThat(route("Notes S2", "Quand les notes seront-elles publiées ?", Intent.QUESTION, Sensitivity.LOW,
                true, SlotType.GRADES_PUBLICATION_DATE))
                .isEqualTo(new ClassificationRouter.Route(EmailStatus.RESOLVING, "resolve_slots"));
    }

    @Test
    void sensitiveLabelEscalatesEvenWithoutReply() {
        assertThat(route("Merci", "Merci pour votre aide.", Intent.INFORMATION, Sensitivity.HEALTH, false))
                .isEqualTo(new ClassificationRouter.Route(EmailStatus.ESCALATED, "sensitive_health"));
    }

    @Test
    void legalThreatEscalatesEvenIfTheModelSaidLow() {
        // the model was fooled (or obeyed the email): LOW, QUESTION, an automatable slot
        assertThat(route("Note", "Sans réponse sous 8 jours, je saisirai le médiateur et mon avocat.",
                Intent.QUESTION, Sensitivity.LOW, true, SlotType.PROCEDURE).reason())
                .isEqualTo("sensitive_keyword");
    }

    @Test
    void individualResultIsNeverAutomated() {
        assertThat(route("Ma note", "Quelle note ai-je eue ?", Intent.QUESTION, Sensitivity.LOW, true,
                SlotType.INDIVIDUAL_RESULT).reason())
                .isEqualTo("not_automatable");
    }

    @Test
    void thanksAreIgnoredAndUnknownQuestionsEscalate() {
        assertThat(route("Merci", "Merci beaucoup !", Intent.INFORMATION, Sensitivity.LOW, false).next())
                .isEqualTo(EmailStatus.IGNORED);
        assertThat(route("Parking", "Où puis-je me garer ?", Intent.QUESTION, Sensitivity.LOW, true).reason())
                .isEqualTo("no_registered_slot");
    }

    @Test
    void aQuestionWithASlotIsAnsweredEvenIfTheModelSaysNoReply() {
        // measured on qwen2.5: a plain question labelled INFORMATION with replyNeeded=false, but with its slot
        assertThat(route("S2 grades", "When will the S2 exam grades be published?", Intent.INFORMATION,
                Sensitivity.LOW, false, SlotType.GRADES_PUBLICATION_DATE).next()).isEqualTo(EmailStatus.RESOLVING);
    }

    @Test
    void missingLabelsFailClosed() {
        assertThat(route("x", "y", null, Sensitivity.LOW, true).next()).isEqualTo(EmailStatus.ESCALATED);
    }

    @Test
    void frenchShortIsNotACourtCase() {
        assertThat(route("Délai", "Le délai est un peu court, quelle est la date limite ?", Intent.QUESTION,
                Sensitivity.LOW, true, SlotType.DEADLINE).next())
                .isEqualTo(EmailStatus.RESOLVING);
    }

    private static ClassificationRouter.Route route(String subject, String body, Intent intent, Sensitivity sensitivity,
            boolean replyNeeded, SlotType... slots) {
        List<EmailSnapshot.Slot> slotViews = Arrays.stream(slots)
                .map(t -> new EmailSnapshot.Slot(1, t, "s2 exams", null, "?", SlotOwner.USER, SlotStatus.UNRESOLVED,
                        null, null))
                .toList();
        return ClassificationRouter.route(new EmailSnapshot(1, 0, EmailStatus.CLASSIFIED, "<m@x>", "t", "a@univ.example", "A",
                subject, body, Instant.now(), SenderScope.UNKNOWN, "student", intent, sensitivity, Language.FR, replyNeeded, null,
                slotViews));
    }
}
