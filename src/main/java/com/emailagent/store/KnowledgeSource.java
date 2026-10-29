package com.emailagent.store;

import com.emailagent.model.SenderScope;
import com.emailagent.model.SlotKey;
import com.emailagent.model.SlotType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A read-only trusted source of scoped answers (knowledge base, calendar), loaded from a classpath JSON file.
 * An entry with no scope applies to every sender; a scoped entry only to a sender with exactly that scope.
 */
public abstract class KnowledgeSource {

    public record Entry(String id, String title, String date, SlotType slotType, String entity, String qualifier,
            String program, String track, String year, String value, String source) {

        boolean appliesTo(SlotKey key, SenderScope scope) {
            if (!new SlotKey(slotType, entity, qualifier).equals(key)) {
                return false;
            }
            boolean general = program == null && track == null && year == null;
            return general || (scope.isKnown() && Objects.equals(program, scope.program())
                    && Objects.equals(track, scope.track()) && Objects.equals(year, scope.year()));
        }
    }

    private final List<Entry> entries;

    protected KnowledgeSource(ObjectMapper mapper, String fixture) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(fixture)) {
            if (in == null) {
                throw new IllegalStateException("knowledge fixture not found: " + fixture);
            }
            entries = List.copyOf(mapper.readValue(in, new TypeReference<List<Entry>>() {}));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read knowledge fixture " + fixture, e);
        }
    }

    public Optional<Entry> find(SlotKey key, SenderScope scope) {
        return entries.stream().filter(e -> e.appliesTo(key, scope)).findFirst();
    }
}
