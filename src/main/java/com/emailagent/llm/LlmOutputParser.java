package com.emailagent.llm;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.TreeSet;

/** Turns raw model text into a validated object, or refuses. Shared by every LlmClient, real or fake. */
@ApplicationScoped
public class LlmOutputParser {

    public static final int MAX_OUTPUT_CHARS = 20_000;

    // Our own mapper: the app-wide one ignores unknown fields, this one refuses anything unexpected
    private static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    private final Validator validator;

    public LlmOutputParser(Validator validator) {
        this.validator = validator;
    }

    public <T> T parse(String raw, Class<T> type) throws LlmException {
        if (raw == null || raw.isBlank() || raw.length() > MAX_OUTPUT_CHARS) {
            throw invalid(type, "empty or longer than " + MAX_OUTPUT_CHARS + " characters");
        }
        T value;
        try {
            value = STRICT.readValue(raw, type);
        } catch (JacksonException e) {
            // Not e.getMessage(): Jackson quotes the offending value, which is the attacker's text
            throw invalid(type, e.getClass().getSimpleName());
        }
        if (value == null) {
            throw invalid(type, "null");
        }
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            Set<String> fields = new TreeSet<>();
            violations.forEach(v -> fields.add(v.getPropertyPath().toString()));
            throw invalid(type, "constraint violated on " + fields);
        }
        return value;
    }

    private static LlmException invalid(Class<?> type, String why) {
        return new LlmException(LlmException.Reason.INVALID_OUTPUT, type.getSimpleName() + ": " + why);
    }
}
