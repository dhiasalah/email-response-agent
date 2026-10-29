package com.emailagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.service.output.JsonSchemas;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@QuarkusTest
@Tag("ollama") // needs a running Ollama: excluded from normal builds and CI
class OllamaSmokeTest {

    // An enum becomes a JSON-schema "enum", which the decoder enforces; a regex @Pattern is not
    enum Language { fr, en }

    // required = true puts the field in the schema's "required" list (LangChain4j makes fields optional by default)
    // and makes Jackson reject an answer that leaves it out
    record Classification(
            @JsonProperty(required = true) @NotBlank String intent,
            @JsonProperty(required = true) @NotNull Boolean replyNeeded, // Boolean: missing must fail, not become false
            @JsonProperty(required = true) @NotNull Language language) {}

    static final String EMAIL =
            "Bonjour Monsieur, quand les notes du S2 seront-elles publiées ? Merci, Lina (M2 IA)";

    @Inject ChatModel chat;
    @Inject ObjectMapper mapper;
    @Inject Validator validator;

    @Test
    void ollamaAnswersWithSchemaValidJson() throws Exception {
        JsonSchema schema = JsonSchemas.jsonSchemaFrom(Classification.class).orElseThrow();
        System.out.println("schema: " + schema.rootElement());

        ChatRequest request = ChatRequest.builder()
                .messages(
                        SystemMessage.from("Classify the email. Answer only with JSON."),
                        UserMessage.from(EMAIL))
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormatType.JSON)
                        .jsonSchema(schema) // constrained decoding to this schema
                        .build())
                .build();

        String raw = chat.chat(request).aiMessage().text();
        System.out.println("raw: " + raw);

        // Never trust: parse strictly, then validate again in Java
        Classification parsed = mapper.readValue(raw, Classification.class);
        System.out.println("parsed: " + parsed);
        assertThat(validator.validate(parsed)).isEmpty();
    }
}
