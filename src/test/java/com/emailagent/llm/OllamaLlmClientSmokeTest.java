package com.emailagent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.emailagent.model.Intent;
import com.emailagent.model.Language;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Wiring check against the real Ollama: both tiers answer with schema-valid, strictly parsed JSON. */
@QuarkusTest
@Tag("ollama")
class OllamaLlmClientSmokeTest {

    record Classification(
            @JsonProperty(required = true) @NotNull Intent intent,
            @JsonProperty(required = true) @NotNull Language language) {}

    @Inject LlmClient llm;

    @Test
    void bothTiersAnswerWithValidJson() throws LlmException {
        for (ModelTier tier : ModelTier.values()) {
            Classification out = llm.call(new LlmRequest("smoke", tier,
                    "Classify the email: intent and language. Answer only with JSON.",
                    "Bonjour Monsieur, quand les notes du S2 seront-elles publiées ? Merci, Lina"), Classification.class);

            // Wiring, not accuracy: qwen2.5 sometimes says REQUEST here (valid != correct; accuracy is measured separately)
            assertThat(out.intent()).isIn(Intent.QUESTION, Intent.REQUEST);
            assertThat(out.language()).isEqualTo(Language.FR);
        }
    }
}
