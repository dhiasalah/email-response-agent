package com.emailagent.orchestrator;

import com.emailagent.llm.LlmException;
import com.emailagent.model.EmailStatus;

/**
 * One agent as the orchestrator sees it (design doc §3): triggered by one status, never calls another agent,
 * never touches the database in {@link #run}. Its writes travel back inside the {@link StepResult}.
 */
public interface AgentStep {

    /** Short name written to the decision log ("triage", "writer"…). */
    String name();

    /** The status that triggers this step. One step per status, checked at start-up. */
    EmailStatus trigger();

    /** The slow part (LLM calls), run outside any transaction. Throwing escalates the email. */
    StepResult run(EmailSnapshot email) throws LlmException;
}
