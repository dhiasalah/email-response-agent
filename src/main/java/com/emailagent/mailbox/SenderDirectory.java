package com.emailagent.mailbox;

import com.emailagent.config.AppConfig;
import com.emailagent.model.SenderRole;
import com.emailagent.model.SenderScope;
import com.emailagent.security.SenderAuthentication;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    record Entry(String email, String program, String track, String year, SenderRole role) {}

    private final Map<String, SenderScope> byAddress = new HashMap<>();
    private final Map<String, SenderRole> roles = new HashMap<>();

    public SenderDirectory(ObjectMapper mapper, AppConfig config) {
        String fixture = config.senderDirectoryFixture();
        try (InputStream in = open(fixture)) {
            if (in == null) {
                throw new IllegalStateException("sender directory not found: " + fixture);
            }
            for (Entry e : mapper.readValue(in, new TypeReference<List<Entry>>() {})) {
                String address = e.email().toLowerCase(Locale.ROOT);
                byAddress.put(address, new SenderScope(e.program(), e.track(), e.year()));
                roles.put(address, e.role() == null ? SenderRole.STUDENT : e.role());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read sender directory " + fixture, e);
        }
    }

    /**
     * A classpath fixture, or {@code file:<path>} for a roster kept outside the project (real addresses never go
     * into the repository).
     */
    static InputStream open(String fixture) throws IOException {
        if (fixture.startsWith("file:")) {
            Path path = Path.of(fixture.substring("file:".length()));
            return Files.exists(path) ? Files.newInputStream(path) : null;
        }
        return Thread.currentThread().getContextClassLoader().getResourceAsStream(fixture);
    }

    /** Exact address match only: a look-alike address gets no scope. */
    public SenderScope scopeOf(String address) {
        if (address == null) {
            return SenderScope.UNKNOWN;
        }
        return byAddress.getOrDefault(address.toLowerCase(Locale.ROOT), SenderScope.UNKNOWN);
    }

    /**
     * The scope, unless the importer found that the receiving server didn't authenticate the sender: then the From
     * address may be someone else's, and the email gets no scope (design doc §8.1: unknown scope, no reuse).
     */
    public SenderScope scopeOf(String address, String sanitizerFlags) {
        return unverified(sanitizerFlags) ? SenderScope.UNKNOWN : scopeOf(address);
    }

    /** The role, with the same rule: an unauthenticated sender adds no weight to a card. */
    public SenderRole roleOf(String address, String sanitizerFlags) {
        return unverified(sanitizerFlags) ? SenderRole.UNKNOWN : roleOf(address);
    }

    private static boolean unverified(String sanitizerFlags) {
        return sanitizerFlags != null
                && List.of(sanitizerFlags.split(",")).contains(SenderAuthentication.UNVERIFIED_FLAG);
    }

    /** Role for card priority (design doc §7.4), same exact-match rule: unknown address, no weight. */
    public SenderRole roleOf(String address) {
        if (address == null) {
            return SenderRole.UNKNOWN;
        }
        return roles.getOrDefault(address.toLowerCase(Locale.ROOT), SenderRole.UNKNOWN);
    }
}
