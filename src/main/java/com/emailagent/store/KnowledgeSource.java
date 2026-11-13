package com.emailagent.store;

import com.emailagent.model.SenderScope;
import com.emailagent.model.SlotKey;
import com.emailagent.model.SlotType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A read-only trusted source of scoped answers: one agent's knowledge base or calendar, read from the JSON files its
 * definition names. An entry with no scope applies to every sender of that agent; a scoped entry only to a sender
 * with exactly that scope.
 */
public final class KnowledgeSource {

    @JsonIgnoreProperties(ignoreUnknown = false)
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

    public static final KnowledgeSource EMPTY = new KnowledgeSource(List.of());

    private final List<Entry> entries;

    public KnowledgeSource(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** All entries of these files, in order. A missing or malformed file stops the start-up. */
    public static KnowledgeSource load(ObjectMapper mapper, List<Path> files) {
        List<Entry> all = new ArrayList<>();
        for (Path file : files) {
            try (InputStream in = Files.newInputStream(file)) {
                List<Entry> entries = mapper.readValue(in, new TypeReference<List<Entry>>() {});
                for (Entry e : entries) {
                    if (e.id() == null || e.slotType() == null || e.entity() == null || e.value() == null) {
                        throw new IllegalStateException("knowledge entry without id, slotType, entity or value in "
                                + file);
                    }
                }
                all.addAll(entries);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read knowledge file " + file, e);
            }
        }
        return new KnowledgeSource(all);
    }

    public Optional<Entry> find(SlotKey key, SenderScope scope) {
        return entries.stream().filter(e -> e.appliesTo(key, scope)).findFirst();
    }

    public List<Entry> entries() {
        return entries;
    }
}
