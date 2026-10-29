package com.emailagent.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.emailagent.llm.LlmException;
import com.emailagent.mailbox.Mailbox;
import com.emailagent.mailbox.SentMessage;
import com.emailagent.model.SlotType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The real model reading the demo Sent folder: learns the owner's date; learns nothing from "not yet" with a quoted
 * attack below it (before the shape check, qwen2.5 returned the "not yet" sentence as the answer).
 */
@QuarkusTest
@Tag("ollama")
class CuratorOllamaTest {

    @Inject CuratorAgent curator;
    @Inject Mailbox mailbox;

    static final List<CuratorAgent.Question> GRADES = List.of(new CuratorAgent.Question(1,
            SlotType.GRADES_PUBLICATION_DATE, "s2 exams", null, "When will the S2 exam grades be published?"));

    @Test
    void learnsTheOwnersAnswer() throws LlmException {
        List<CuratorAgent.Learned> learned = curator.extract(GRADES, sent("<s01@univ.example>").bodyText());
        System.out.println("s01 -> " + learned);
        assertThat(learned).singleElement().satisfies(l -> assertThat(l.value()).isEqualTo("2027-06-18"));
    }

    @Test
    void learnsNothingFromAQuotedClaim() throws LlmException {
        List<CuratorAgent.Learned> learned = curator.extract(GRADES, sent("<s02@univ.example>").bodyText());
        System.out.println("s02 -> " + learned);
        assertThat(learned).isEmpty();
    }

    private SentMessage sent(String id) {
        return mailbox.fetchSent().stream().filter(m -> m.messageId().equals(id)).findFirst().orElseThrow();
    }
}
