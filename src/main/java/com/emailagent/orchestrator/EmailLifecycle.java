package com.emailagent.orchestrator;

import static com.emailagent.model.EmailStatus.*;

import com.emailagent.model.EmailStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The email state machine (design doc §6): which status may follow which. Anything not listed is refused.
 * The database trigger in V0501 holds the same table, so even code that bypasses this class can't skip a step.
 */
public final class EmailLifecycle {

    private static final Map<EmailStatus, Set<EmailStatus>> NEXT = new EnumMap<>(EmailStatus.class);

    static {
        // every active status may escalate: when anything goes wrong, a human takes over (fail closed)
        NEXT.put(NEW, EnumSet.of(FILTERED_OUT, TRIAGED, ESCALATED));
        NEXT.put(TRIAGED, EnumSet.of(IGNORED, RESOLVING, ESCALATED));
        NEXT.put(RESOLVING, EnumSet.of(READY, AWAITING_SENDER, AWAITING_INPUT, ESCALATED));
        NEXT.put(AWAITING_SENDER, EnumSet.of(TRIAGED, CLOSED, ESCALATED));
        NEXT.put(AWAITING_INPUT, EnumSet.of(READY, ESCALATED));
        NEXT.put(READY, EnumSet.of(DRAFTED, ESCALATED));
        NEXT.put(DRAFTED, EnumSet.of(REVIEWED, ESCALATED));
        NEXT.put(REVIEWED, EnumSet.of(SENT, DRAFT_SAVED, ESCALATED));
        NEXT.put(DRAFT_SAVED, EnumSet.of(SENT, ESCALATED));
        // the owner retries the writer (READY), replies from the panel (SENT, with an owner review), or closes
        NEXT.put(ESCALATED, EnumSet.of(READY, SENT, CLOSED));
        for (EmailStatus terminal : EnumSet.of(FILTERED_OUT, IGNORED, SENT, CLOSED)) {
            NEXT.put(terminal, EnumSet.noneOf(EmailStatus.class));
        }
    }

    private EmailLifecycle() {}

    public static boolean allowed(EmailStatus from, EmailStatus to) {
        return from != null && to != null && NEXT.get(from).contains(to);
    }

    public static Set<EmailStatus> next(EmailStatus from) {
        return Set.copyOf(NEXT.get(from));
    }

    /** Statuses that wait on someone outside the system (the sender, the owner): timers check them, they may stay. */
    public static boolean isWaiting(EmailStatus status) {
        return status == AWAITING_SENDER || status == AWAITING_INPUT;
    }

    public static boolean isTerminal(EmailStatus status) {
        return NEXT.get(status).isEmpty();
    }
}
