package com.emailagent.llm;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A deterministic embedder for tests. Unknown texts get a pseudo-random vector seeded by the text (unrelated texts
 * are nearly orthogonal); a test pins the vectors it needs to be "close" with {@link #pin}.
 */
public class FakeEmbedder implements Embedder {

    static final int DIMENSIONS = 64;

    private final Map<String, float[]> pinned = new HashMap<>();
    private final Set<String> failing = new HashSet<>();
    private int calls;

    public FakeEmbedder pin(String text, float... vector) {
        pinned.put(text, vector);
        return this;
    }

    public FakeEmbedder failOn(String text) {
        failing.add(text);
        return this;
    }

    public int calls() {
        return calls;
    }

    @Override
    public float[] embed(String text) throws LlmException {
        calls++;
        if (failing.contains(text)) {
            throw new LlmException(LlmException.Reason.UNAVAILABLE, "FakeEmbedder: down");
        }
        float[] v = pinned.get(text);
        if (v != null) {
            float[] padded = new float[DIMENSIONS];
            System.arraycopy(v, 0, padded, 0, Math.min(v.length, DIMENSIONS));
            return padded;
        }
        Random random = new Random(text.hashCode());
        float[] out = new float[DIMENSIONS];
        for (int i = 0; i < DIMENSIONS; i++) {
            out[i] = (float) random.nextGaussian();
        }
        return out;
    }

    @Override
    public String modelName() {
        return "fake-embedder";
    }
}
