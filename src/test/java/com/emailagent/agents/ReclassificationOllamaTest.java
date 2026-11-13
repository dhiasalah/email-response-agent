package com.emailagent.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.emailagent.llm.LlmClient;
import com.emailagent.llm.LlmException;
import com.emailagent.llm.LlmRequest;
import com.emailagent.llm.ModelTier;
import com.emailagent.model.EmailStatus;
import com.emailagent.model.SenderScope;
import com.emailagent.model.SlotOwner;
import com.emailagent.model.SlotType;
import com.emailagent.step.EmailSnapshot;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Classification again on an email with the sender's merged answer (design doc §7.5): the gap is now known (printed). */
@QuarkusTest
@Tag("ollama")
class ReclassificationOllamaTest {

    @Inject LlmClient llm;

    @Test
    void theMergedAnswerTurnsASenderGapIntoAQuestionTheOwnerCanAnswer() throws LlmException {
        ClassifierOutput before = classify("Hello, I have a question: when is the exam? Thanks, Lina");
        ClassifierOutput after = classify("Hello, I have a question: when is the exam? Thanks, Lina\n\n"
                + "[Reply of the sender]\nThe machine learning exam, first session.");
        before.questions().forEach(q -> System.out.println("BEFORE -> " + q.slotType() + " " + q.owner() + " "
                + q.entity()));
        after.questions().forEach(q -> System.out.println("AFTER -> " + q.slotType() + " " + q.owner() + " "
                + q.entity()));

        assertThat(after.suspicious()).isFalse();
        assertThat(after.questions()).anySatisfy(q -> {
            assertThat(q.slotType()).isEqualTo(SlotType.EXAM_DATE);
            assertThat(q.owner()).isEqualTo(SlotOwner.USER);
            assertThat(q.entity()).contains("machine learning");
        });
    }

    private ClassifierOutput classify(String body) throws LlmException {
        EmailSnapshot email = new EmailSnapshot(1, 0, EmailStatus.NEW, "<x@test>", null, "s@univ.example", "S",
                "Exam", body, Instant.now(), SenderScope.UNKNOWN, "student", null, null, null, null, null, List.of());
        return llm.call(new LlmRequest("classifier", ModelTier.FAST, ClassifierAgent.INSTRUCTIONS, ClassifierAgent.render(email)),
                ClassifierOutput.class);
    }
}
