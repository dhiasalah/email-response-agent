package com.emailagent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SlotKeyTest {

    @Test
    void spellingVariantsGiveTheSameKey() {
        SlotKey a = new SlotKey(SlotType.EXAM_DATE, "S2 Exams", null);
        assertThat(new SlotKey(SlotType.EXAM_DATE, "s2  exam", "")).isEqualTo(a);
        assertThat(new SlotKey(SlotType.EXAM_DATE, "S2-exams", " ")).isEqualTo(a);
        assertThat(a.entity()).isEqualTo("s2 exam");
        assertThat(SlotKey.normalize("Système multi-agents")).isEqualTo("systeme multi agent");
    }

    @Test
    void typeAndQualifierStillSeparateKeys() {
        SlotKey exam = new SlotKey(SlotType.EXAM_ROOM, "s2 exams", null);
        assertThat(new SlotKey(SlotType.EXAM_ROOM, "s2 exams", "retake")).isNotEqualTo(exam);
        assertThat(new SlotKey(SlotType.EXAM_DATE, "s2 exams", null)).isNotEqualTo(exam);
        assertThat(SlotKey.normalize("class")).isEqualTo("class"); // "ss" is not a plural
    }

    @Test
    void emptyEntityIsRefused() {
        assertThatThrownBy(() -> new SlotKey(SlotType.DEADLINE, " -- ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
