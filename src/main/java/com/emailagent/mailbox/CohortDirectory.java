package com.emailagent.mailbox;

import com.emailagent.config.AppConfig;
import com.emailagent.model.SenderScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where a cohort can be reached as a whole (design doc §7.7, channels configured per scope). A trusted fixture: a
 * broadcast's recipient always comes from here, never from an email or a model.
 */
@ApplicationScoped
public class CohortDirectory {

    record Entry(String program, String track, String year, String list) {}

    private final Map<SenderScope, String> lists = new HashMap<>();

    public CohortDirectory(ObjectMapper mapper, AppConfig config) {
        String fixture = config.cohortDirectoryFixture();
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(fixture)) {
            if (in == null) {
                return; // no cohort lists: broadcasts are never suggested
            }
            for (Entry e : mapper.readValue(in, new TypeReference<List<Entry>>() {})) {
                lists.put(new SenderScope(e.program(), e.track(), e.year()), e.list());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read cohort directory " + fixture, e);
        }
    }

    /** Exact scope only: no list for a scope means no broadcast. */
    public Optional<String> listFor(SenderScope scope) {
        return scope == null || !scope.isKnown() ? Optional.empty() : Optional.ofNullable(lists.get(scope));
    }
}
