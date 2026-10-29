package com.emailagent.model;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * What a slot asks for, in comparable form: type + entity + qualifier (design doc §7.1).
 * Two questions are "the same" only if their keys are equal; semantic closeness is grouping's job, not memory's.
 */
public record SlotKey(SlotType type, String entity, String qualifier) {

    public SlotKey {
        Objects.requireNonNull(type, "type");
        entity = normalize(entity);
        qualifier = qualifier == null || normalize(qualifier).isEmpty() ? null : normalize(qualifier);
        if (entity.isEmpty()) {
            throw new IllegalArgumentException("entity is empty");
        }
    }

    /** "S2 Exams", "s2  exam", "S2-exams" → "s2 exam": lower case, no accents, no punctuation, no plural s. */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String folded = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
        return Arrays.stream(folded.split(" "))
                .filter(w -> !w.isEmpty())
                .map(w -> w.length() > 3 && w.endsWith("s") && !w.endsWith("ss") ? w.substring(0, w.length() - 1) : w)
                .collect(Collectors.joining(" "));
    }
}
