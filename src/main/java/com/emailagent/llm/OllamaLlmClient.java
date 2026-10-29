package com.emailagent.llm;

import com.emailagent.security.UntrustedEnvelope;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.service.output.JsonSchemas;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The real LlmClient: Ollama through LangChain4j. Constrained decoding to the output type's JSON schema,
 * strict parsing, one retry on invalid output, then failure. No tools are ever given to the model.
 */
@ApplicationScoped
public class OllamaLlmClient implements LlmClient {

    static final int MAX_ATTEMPTS = 2;

    private final ChatModel fast;
    private final ChatModel strong;
    private final LlmOutputParser parser;

    public OllamaLlmClient(ChatModel fast, @ModelName("strong") ChatModel strong, LlmOutputParser parser) {
        this.fast = fast;
        this.strong = strong;
        this.parser = parser;
    }

    @Override
    public <T> T call(LlmRequest request, Class<T> outputType) throws LlmException {
        JsonSchema schema = JsonSchemas.jsonSchemaFrom(outputType)
                .orElseThrow(() -> new IllegalArgumentException("no JSON schema for " + outputType.getSimpleName()));
        ChatModel model = request.tier() == ModelTier.STRONG ? strong : fast;

        String retryNote = "";
        LlmException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String raw = send(model, request, schema, retryNote);
            try {
                return parser.parse(raw, outputType);
            } catch (LlmException e) {
                last = e;
                // e's message holds only our field names, never the model's text, so it is safe to log and to echo
                Log.warnf("%s: invalid output, attempt %d/%d: %s", request.agent(), attempt, MAX_ATTEMPTS, e.getMessage());
                retryNote = "\n\nYour previous answer was rejected (" + e.getMessage()
                        + "). Answer again with JSON that matches the schema exactly.";
            }
        }
        throw last;
    }

    private String send(ChatModel model, LlmRequest request, JsonSchema schema, String retryNote) throws LlmException {
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(
                        SystemMessage.from(request.instructions() + "\n\n" + UntrustedEnvelope.RULES + retryNote),
                        UserMessage.from(UntrustedEnvelope.wrap(request.untrustedInput())))
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormatType.JSON)
                        .jsonSchema(schema)
                        .build())
                .build();
        try {
            return model.chat(chatRequest).aiMessage().text();
        } catch (RuntimeException e) {
            if (isTimeout(e)) {
                throw new LlmException(LlmException.Reason.TIMEOUT, request.agent() + ": model timed out");
            }
            Log.warnf("%s: model call failed (%s)", request.agent(), e.getClass().getSimpleName());
            throw new LlmException(LlmException.Reason.UNAVAILABLE, request.agent() + ": model unavailable");
        }
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException
                    || t instanceof java.util.concurrent.TimeoutException
                    || t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
