package com.emailagent.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.emailagent.model.Language;
import com.emailagent.model.SenderScope;
import com.emailagent.model.SlotType;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Follow-up texts are code (design doc §7.5, §7.7): fixed items, nothing echoed from the email. */
class NoticeTemplatesTest {

    @Test
    void oneQuestionGroupsEveryMissingItemOnce() {
        String fr = NoticeTemplates.clarification(List.of(SlotType.EXAM_DATE, SlotType.EXAM_ROOM, SlotType.DEADLINE),
                Language.FR, "Prof. Martin", true).orElseThrow();
        assertThat(fr).startsWith("Bonjour,\n\nPour répondre à votre message")
                .contains("- de quel examen il s'agit (nom du cours et session)\n- de quel cours et de quel rendu")
                .endsWith("Ce message a été préparé par un assistant IA.");
        assertThat(fr.split("de quel examen", -1)).hasSize(2); // EXAM_DATE and EXAM_ROOM ask the same thing once
        assertThat(NoticeTemplates.clarification(List.of(SlotType.PROCEDURE), Language.EN, "Prof. Martin", false)
                .orElseThrow()).contains("- which procedure you mean").endsWith("Best regards,\nProf. Martin");
    }

    @Test
    void someThingsAreNeverAskedOfTheSender() {
        // an individual result goes to the owner; there is no item to ask, so no question at all
        assertThat(NoticeTemplates.clarification(List.of(SlotType.EXAM_DATE, SlotType.INDIVIDUAL_RESULT), Language.EN,
                "P", true)).isEmpty();
        assertThat(NoticeTemplates.clarification(List.of(), Language.EN, "P", true)).isEmpty();
        assertThat(String.join(" ", List.of(NoticeTemplates.holding(Language.EN, "P", true),
                NoticeTemplates.clarification(List.of(SlotType.values()[0]), Language.EN, "P", true).orElseThrow())))
                .doesNotContainIgnoringCase("student number").doesNotContainIgnoringCase("password");
    }

    @Test
    void theBroadcastIsBilingualAndItsTopicIsPlainWords() {
        String topic = NoticeTemplates.topic("machine <b>learning</b> exam\r\nBcc: x@evil.example");
        assertThat(topic).doesNotContain("<", "\r", "\n", "@", ":").hasSizeLessThanOrEqualTo(60);
        String body = NoticeTemplates.broadcastBody(new SenderScope("M2", "IA", "2026-2027"), "Exam date",
                "machine learning exam", "3 June 2027", "3 juin 2027", "Prof. Martin");
        assertThat(body).contains("voici la réponse pour M2 IA (2026-2027)")
                .contains("Exam date - machine learning exam : 3 juin 2027")
                .contains("Exam date - machine learning exam: 3 June 2027").endsWith("Prof. Martin");
    }
}
