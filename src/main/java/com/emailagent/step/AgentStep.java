package com.emailagent.step;

import com.emailagent.llm.LlmException;
import com.emailagent.model.EmailStatus;

/**
 * One agent as the orchestrator sees it (design doc §3): triggered by one status, never calls another agent,
 * never writes to the database in {@link #run} (short read-only lookups are fine). Its writes travel back inside
 * the {@link StepResult} and are committed with the status change.
 */
public interface AgentStep {

    /** Short name written to the decision log ("triage", "writer"…). */
    String name();

    /** The status that triggers this step. One step per status, checked at start-up. */
    EmailStatus trigger();

    /** The slow part (LLM calls, lookups), run outside the commit transaction. Throwing escalates the email. */
    StepResult run(EmailSnapshot email) throws LlmException;
}
