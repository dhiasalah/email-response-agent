package com.emailagent.llm;

/**
 * The only way an agent talks to a model (design doc §3). Agents depend on this interface, never on LangChain4j.
 *
 * <p>Guarantees of {@link #call}: the result is never null, was parsed strictly and passed Bean Validation.
 * Anything else (bad JSON, timeout, model down) ends in an {@link LlmException}, after at most one retry.
 */
public interface LlmClient {

    <T> T call(LlmRequest request, Class<T> outputType) throws LlmException;
}
