package com.emailagent.llm;

/**
 * Turns a short text into a vector for similarity (design doc §7.2). Behind our own interface, like LlmClient:
 * grouping depends on this, never on LangChain4j, and tests use a deterministic fake.
 */
public interface Embedder {

    /** Never null, never empty. Failures (model down, timeout) are an {@link LlmException}. */
    float[] embed(String text) throws LlmException;

    /** Vectors from different models are not comparable; clusters remember which model made their centroid. */
    String modelName();
}
