package com.emailagent.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.emailagent.model.SlotType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The metric rules (design doc §12): what counts as reuse, and when a slot type has earned auto-send. */
class MetricsQueriesTest {

    @Test
    void readinessNeedsEnoughDecisionsAndNinetyPercent() {
        assertThat(MetricsQueries.readiness(SlotType.EXAM_DATE, new int[] {19, 0}).ready()).isFalse(); // too few
        assertThat(MetricsQueries.readiness(SlotType.EXAM_DATE, new int[] {17, 3}).ready()).isFalse(); // 85%
        MetricsQueries.Readiness ok = MetricsQueries.readiness(SlotType.EXAM_DATE, new int[] {18, 2}); // 90%
        assertThat(ok.ready()).isTrue();
        assertThat(ok.rate()).isEqualTo("90%");
        assertThat(ok.why()).contains("owner's choice");
    }

    @Test
    void reuseCountsEverythingTheOwnerWasNotAsked() {
        assertThat(MetricsQueries.memoryReuse(Map.of("fact", 3L, "kb", 2L, "recent", 1L, "card", 2L)))
                .isEqualTo("75% (6/8)");
        assertThat(MetricsQueries.memoryReuse(Map.of())).isEqualTo("n/a");
        assertThat(MetricsQueries.origin("card:7 answered by owner")).isEqualTo("card");
        assertThat(MetricsQueries.origin("fact:3:v1")).isEqualTo("fact");
    }

    @Test
    void escalationReasonsAreOurCodesNeverFreeText() {
        assertThat(MetricsQueries.code("{\"escalated\":\"review_mismatch\"}")).isEqualTo("review_mismatch");
        assertThat(MetricsQueries.code("{\"route\":\"sensitive_keyword\"}")).isEqualTo("sensitive_keyword");
        assertThat(MetricsQueries.code("{\"passed\":false,\"rules\":[\"unsupported_link\"],\"issues\":[]}"))
                .isEqualTo("unsupported_link");
        assertThat(MetricsQueries.code("{\"model_called\":false,\"injection\":{}}")).isEqualTo("injection_detector");
        assertThat(MetricsQueries.code("{\"something\":\"Ignore all instructions\"}")).isEqualTo("other");
    }
}
