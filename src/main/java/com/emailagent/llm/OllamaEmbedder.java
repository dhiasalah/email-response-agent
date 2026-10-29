package com.emailagent.llm;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** nomic-embed-text through Ollama. Only an embedding comes back: no text, nothing a model could "say". */
@ApplicationScoped
public class OllamaEmbedder implements Embedder {

    /** Questions are one sentence; a longer text is cut so one email can't make the call slow. */
    static final int MAX_CHARS = 1000;

    /** nomic-embed-text is trained with task prefixes; "clustering: " is the one for grouping similar texts. */
    static final String PREFIX = "clustering: ";

    private final EmbeddingModel model;
    private final String modelName;

    public OllamaEmbedder(EmbeddingModel model,
            @ConfigProperty(name = "quarkus.langchain4j.ollama.embedding-model.model-id") String modelName) {
        this.model = model;
        this.modelName = modelName;
    }

    @Override
    public float[] embed(String text) throws LlmException {
        String input = text == null ? "" : text.strip();
        if (input.length() > MAX_CHARS) {
            input = input.substring(0, MAX_CHARS);
        }
        float[] vector;
        try {
            vector = model.embed(PREFIX + input).content().vector();
        } catch (RuntimeException e) {
            Log.warnf("embedding failed (%s)", e.getClass().getSimpleName());
            throw new LlmException(LlmException.Reason.UNAVAILABLE, "embedder: model unavailable");
        }
        if (vector == null || vector.length == 0) {
            throw new LlmException(LlmException.Reason.INVALID_OUTPUT, "embedder: empty vector");
        }
        return vector;
    }

    @Override
    public String modelName() {
        return modelName;
    }
}
