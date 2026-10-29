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
import java.util.Locale;
import java.util.Map;

/**
 * Who a sender is (program, track, year), from a trusted roster: never from what the email claims ("I'm in M2 IA").
 * Unknown sender = unknown scope, which disables memory reuse for that email (design doc §4.1, §8).
 * A JSON fixture for now; an official student directory can replace it.
 */
@ApplicationScoped
public class SenderDirectory {

    record Entry(String email, String program, String track, String year) {}

    private final Map<String, SenderScope> byAddress = new HashMap<>();

    public SenderDirectory(ObjectMapper mapper, AppConfig config) {
        String fixture = config.senderDirectoryFixture();
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(fixture)) {
            if (in == null) {
                throw new IllegalStateException("sender directory not found: " + fixture);
            }
            for (Entry e : mapper.readValue(in, new TypeReference<List<Entry>>() {})) {
                byAddress.put(e.email().toLowerCase(Locale.ROOT), new SenderScope(e.program(), e.track(), e.year()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read sender directory " + fixture, e);
        }
    }

    /** Exact address match only: a look-alike address gets no scope. */
    public SenderScope scopeOf(String address) {
        if (address == null) {
            return SenderScope.UNKNOWN;
        }
        return byAddress.getOrDefault(address.toLowerCase(Locale.ROOT), SenderScope.UNKNOWN);
    }
}
