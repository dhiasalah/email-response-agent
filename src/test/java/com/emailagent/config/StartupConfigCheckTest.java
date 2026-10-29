package com.emailagent.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.emailagent.model.SlotType;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Configuration may narrow the send policy, never widen it (design doc §9). */
class StartupConfigCheckTest {

    @Test
    void personalSlotTypesCannotBeConfiguredForAutoSend() {
        assertThatCode(() -> StartupConfigCheck.checkAutoSendTypes(Set.of(SlotType.EXAM_DATE, SlotType.PROCEDURE)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> StartupConfigCheck.checkAutoSendTypes(
                Set.of(SlotType.EXAM_DATE, SlotType.INDIVIDUAL_DECISION, SlotType.MEETING_AVAILABILITY)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MEETING_AVAILABILITY").hasMessageContaining("INDIVIDUAL_DECISION");
    }
}
