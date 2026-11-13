package com.emailagent.store;

import com.emailagent.config.AppConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The specialized agents, read once at start-up from the agents folder (brief §4.2, §5, §13). Each agent comes with
 * its own system prompt and its own context: a knowledge base and a calendar that no other agent reads. Memory
 * learned from an agent's emails (facts, recent answers, cards) carries the agent's id and stays with it too.
 *
 * <p>Everything is checked here, so a broken folder stops the start-up with a clear message: ids unique and
 * well-formed, exactly one fallback agent, every named file present and readable.
 */
@Singleton
public class AgentRegistry {

    /** A loaded agent: its definition, the text of its prompt and its own context. */
    public record Agent(AgentDefinition definition, String prompt, KnowledgeSource knowledge,
            KnowledgeSource calendar) {

        public String id() {
            return definition.id();
        }

        public String name() {
            return definition.name();
        }

        public AgentDefinition.Routing routing() {
            return definition.routing();
        }

        public boolean fallback() {
            return definition.fallback();
        }
    }

    static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,49}");

    private final List<Agent> agents;
    private final Agent fallback;

    @Inject
    public AgentRegistry(ObjectMapper mapper, AppConfig config) {
        this(load(mapper, Path.of(config.agentsDir())));
    }

    /** The agents of a folder, outside CDI (tests, tools). */
    public static AgentRegistry fromFolder(ObjectMapper mapper, Path dir) {
        return new AgentRegistry(load(mapper, dir));
    }

    AgentRegistry(List<Agent> agents) {
        this.agents = agents.stream()
                .sorted(Comparator.comparingInt((Agent a) -> a.definition().order()).thenComparing(Agent::id))
                .toList();
        List<Agent> fallbacks = this.agents.stream().filter(Agent::fallback).toList();
        if (fallbacks.size() != 1) {
            throw new IllegalStateException("exactly one agent must have \"fallback\": true, found " + fallbacks.size());
        }
        this.fallback = fallbacks.get(0);
        if (fallback.routing().membersOnly()) {
            throw new IllegalStateException("the fallback agent " + fallback.id() + " cannot have members");
        }
    }

    /** Every agent, in tie-break order. */
    public List<Agent> all() {
        return agents;
    }

    public Optional<Agent> find(String id) {
        return id == null ? Optional.empty() : agents.stream().filter(a -> a.id().equals(id)).findFirst();
    }

    /** The agent for an email routed to {@code id}; the fallback if that agent no longer exists (file removed). */
    public Agent forEmail(String id) {
        return find(id).orElse(fallback);
    }

    public Agent fallback() {
        return fallback;
    }

    // --- loading ---

    static List<Agent> load(ObjectMapper mapper, Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("agents folder not found: " + dir.toAbsolutePath()
                    + " (set AGENTS_DIR, or run from the project folder)");
        }
        List<Path> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list agents folder " + dir, e);
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("no agent definition (*.json) in " + dir.toAbsolutePath());
        }
        List<Agent> agents = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Path file : files) {
            AgentDefinition d = read(mapper, file);
            String expected = file.getFileName().toString().replaceFirst("\\.json$", "");
            if (d.id() == null || !ID.matcher(d.id()).matches() || !d.id().equals(expected)) {
                throw new IllegalStateException(file + ": \"id\" must be \"" + expected
                        + "\" (the file name; lower case letters, digits and dashes)");
            }
            if (!ids.add(d.id())) {
                throw new IllegalStateException("two agents with the id " + d.id());
            }
            if (d.name() == null || d.name().isBlank()) {
                throw new IllegalStateException(file + ": \"name\" is missing");
            }
            AgentDefinition clean = new AgentDefinition(d.id(), d.name(), d.description(), d.order(), d.fallback(),
                    lowerCase(d.routing()), d.promptFile(), d.knowledge(), d.calendar());
            String prompt = d.promptFile() == null ? "" : readText(inside(dir, d.promptFile(), file));
            KnowledgeSource knowledge = KnowledgeSource.load(mapper, paths(dir, d.knowledge(), file));
            KnowledgeSource calendar = KnowledgeSource.load(mapper, paths(dir, d.calendar(), file));
            agents.add(new Agent(clean, prompt.strip(), knowledge, calendar));
        }
        return agents;
    }

    private static AgentDefinition read(ObjectMapper mapper, Path file) {
        try {
            return mapper.readValue(file.toFile(), AgentDefinition.class);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read agent definition " + file + ": " + e.getMessage(), e);
        }
    }

    /** Addresses, domains and keywords are compared without case: store them that way once. */
    private static AgentDefinition.Routing lowerCase(AgentDefinition.Routing r) {
        return new AgentDefinition.Routing(r.senderRoles(), lower(r.senderAddresses()), lower(r.senderDomains()),
                lower(r.keywords()), lower(r.members()));
    }

    private static List<String> lower(List<String> values) {
        return values.stream().map(v -> v.strip().toLowerCase(Locale.ROOT)).filter(v -> !v.isEmpty()).toList();
    }

    private static List<Path> paths(Path dir, List<String> names, Path definition) {
        return names.stream().map(n -> inside(dir, n, definition)).toList();
    }

    /** A context file must sit inside the agents folder: a definition can't point the agent at any file on disk. */
    static Path inside(Path dir, String name, Path definition) {
        Path root = dir.toAbsolutePath().normalize();
        Path p = root.resolve(name).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalStateException(definition + ": " + name + " is outside the agents folder");
        }
        if (!Files.isRegularFile(p)) {
            throw new IllegalStateException(definition + ": file not found: " + name);
        }
        return p;
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
