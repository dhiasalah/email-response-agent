package com.emailagent.orchestrator;

import com.emailagent.model.EmailStatus;
import com.emailagent.store.EmailEntity;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * What an agent proposes: the next status, what to record in the decision log, and its writes to the shared state.
 * The orchestrator checks {@code next} against the lifecycle, then applies {@code writes}, the status change and the
 * log row in one transaction: all of them or none.
 */
public record StepResult(
        EmailStatus next,
        String output,
        Double confidence,
        String sources,
        String modelVersion,
        Consumer<EmailEntity> writes) {

    public StepResult {
        Objects.requireNonNull(next, "next");
        writes = writes == null ? e -> {} : writes;
    }

    public static StepResult to(EmailStatus next, String output) {
        return new StepResult(next, output, null, null, null, null);
    }

    public StepResult withWrites(Consumer<EmailEntity> writes) {
        return new StepResult(next, output, confidence, sources, modelVersion, writes);
    }
}
