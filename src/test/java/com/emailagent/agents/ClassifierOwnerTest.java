package com.emailagent.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.emailagent.model.SlotOwner;
import org.junit.jupiter.api.Test;

/** Who supplies a missing piece (design doc §7.5): code asks the sender when the question names no particular thing. */
class ClassifierOwnerTest {

    @Test
    void aGenericEntityIsTheSendersToClarify() {
        // measured on qwen2.5: "when is the exam?" came back USER-owned, entity "exam"
        assertThat(ClassifierAgent.owner("exam", SlotOwner.USER)).isEqualTo(SlotOwner.SENDER);
        assertThat(ClassifierAgent.owner("projet", SlotOwner.USER)).isEqualTo(SlotOwner.SENDER);
        assertThat(ClassifierAgent.owner("machine learning exam", SlotOwner.USER)).isEqualTo(SlotOwner.USER);
        // the model may still ask the sender on its own; code only adds cases, never removes one
        assertThat(ClassifierAgent.owner("machine learning exam", SlotOwner.SENDER)).isEqualTo(SlotOwner.SENDER);
    }
}
