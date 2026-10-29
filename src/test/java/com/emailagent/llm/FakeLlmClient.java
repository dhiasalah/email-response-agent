package com.emailagent.llm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A scripted model for tests: returns the raw answers you queue, through the same strict parser as the real client.
 * It lives in src/test, so it can never end up in the application.
 */
public class FakeLlmClient implements LlmClient {

    private final LlmOutputParser parser;
    private final Deque<String> answers = new ArrayDeque<>();
    private final List<LlmRequest> requests = new ArrayList<>();

    public FakeLlmClient(LlmOutputParser parser) {
        this.parser = parser;
    }

    /** Queues the raw text the "model" returns on the next call. */
    public FakeLlmClient willAnswer(String raw) {
        answers.add(raw);
        return this;
    }

    /** Every request received, in order: lets a test check what an agent sent to the model. */
    public List<LlmRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public <T> T call(LlmRequest request, Class<T> outputType) throws LlmException {
        requests.add(request);
        String raw = answers.poll();
        if (raw == null) {
            throw new LlmException(LlmException.Reason.UNAVAILABLE, "FakeLlmClient: no answer queued");
        }
        return parser.parse(raw, outputType);
    }
}
