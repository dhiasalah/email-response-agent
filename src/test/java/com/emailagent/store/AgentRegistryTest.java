package com.emailagent.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.emailagent.model.SenderRole;
import com.emailagent.model.SenderScope;
import com.emailagent.model.SlotKey;
import com.emailagent.model.SlotType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Agents are files (brief §5, §13): the shipped folder loads, and a broken folder stops the start-up clearly. */
class AgentRegistryTest {

    static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path dir;

    @Test
    void theShippedAgentsLoadWithTheirOwnPromptAndContext() {
        AgentRegistry registry = AgentRegistry.fromFolder(MAPPER, Path.of("agents"));

        assertThat(registry.all()).extracting(AgentRegistry.Agent::id)
                .containsExactly("supermas-project", "student", "colleague", "administration", "generic");
        assertThat(registry.fallback().id()).isEqualTo("generic");

        AgentRegistry.Agent student = registry.find("student").orElseThrow();
        assertThat(student.prompt()).contains("Role:").contains("Never disclose");
        assertThat(student.routing().senderRoles()).containsExactly(SenderRole.STUDENT);
        assertThat(student.knowledge().entries()).extracting(KnowledgeSource.Entry::id).contains("kb1", "kb2");
        assertThat(student.calendar().entries()).extracting(KnowledgeSource.Entry::id).contains("ev1");

        // context is per agent: the project's deliverable is unknown to the student agent, and the reverse
        SlotKey deliverable = new SlotKey(SlotType.DEADLINE, "supermasproject deliverable d2.1", null);
        assertThat(registry.find("supermas-project").orElseThrow().knowledge().find(deliverable, SenderScope.UNKNOWN))
                .isPresent();
        assertThat(student.knowledge().find(deliverable, SenderScope.UNKNOWN)).isEmpty();
        assertThat(registry.find("supermas-project").orElseThrow().routing().membersOnly()).isTrue();
    }

    @Test
    void anUnknownIdFallsBackToTheFallbackAgent() {
        AgentRegistry registry = AgentRegistry.fromFolder(MAPPER, Path.of("agents"));

        assertThat(registry.forEmail("removed-agent").id()).isEqualTo("generic");
        assertThat(registry.forEmail(null).id()).isEqualTo("generic");
    }

    @Test
    void aMinimalFolderLoads() throws IOException {
        write("generic.json", "{\"id\": \"generic\", \"name\": \"Generic\", \"fallback\": true}");

        AgentRegistry registry = AgentRegistry.fromFolder(MAPPER, dir);

        assertThat(registry.all()).singleElement().satisfies(a -> {
            assertThat(a.prompt()).isEmpty();
            assertThat(a.knowledge().entries()).isEmpty();
        });
    }

    @Test
    void exactlyOneFallbackIsRequired() throws IOException {
        write("a.json", "{\"id\": \"a\", \"name\": \"A\"}");
        assertThatThrownBy(() -> AgentRegistry.fromFolder(MAPPER, dir)).hasMessageContaining("exactly one agent");

        write("b.json", "{\"id\": \"b\", \"name\": \"B\", \"fallback\": true}");
        write("c.json", "{\"id\": \"c\", \"name\": \"C\", \"fallback\": true}");
        assertThatThrownBy(() -> AgentRegistry.fromFolder(MAPPER, dir)).hasMessageContaining("found 2");
    }

    @Test
    void theIdMustBeTheFileName() throws IOException {
        write("student.json", "{\"id\": \"teacher\", \"name\": \"X\", \"fallback\": true}");

        assertThatThrownBy(() -> AgentRegistry.fromFolder(MAPPER, dir)).hasMessageContaining("must be \"student\"");
    }

    @Test
    void aTypoInAKeyStopsTheStartUp() throws IOException {
        write("generic.json", "{\"id\": \"generic\", \"name\": \"G\", \"fallback\": true, \"promtFile\": \"p.txt\"}");

        assertThatThrownBy(() -> AgentRegistry.fromFolder(MAPPER, dir)).hasMessageContaining("promtFile");
    }

    @Test
    void contextFilesMustExistAndStayInsideTheFolder() throws IOException {
        write("generic.json", "{\"id\": \"generic\", \"name\": \"G\", \"fallback\": true,"
                + " \"knowledge\": [\"missing.json\"]}");
        assertThatThrownBy(() -> AgentRegistry.fromFolder(MAPPER, dir)).hasMessageContaining("file not found");

        write("generic.json", "{\"id\": \"generic\", \"name\": \"G\", \"fallback\": true,"
                + " \"promptFile\": \"../secrets.txt\"}");
        assertThatThrownBy(() -> AgentRegistry.fromFolder(MAPPER, dir)).hasMessageContaining("outside the agents folder");
    }

    @Test
    void theFallbackCannotBeMembersOnly() throws IOException {
        write("generic.json", "{\"id\": \"generic\", \"name\": \"G\", \"fallback\": true,"
                + " \"routing\": {\"members\": [\"a@x.example\"]}}");

        assertThatThrownBy(() -> AgentRegistry.fromFolder(MAPPER, dir)).hasMessageContaining("cannot have members");
    }

    @Test
    void aMissingFolderSaysWhereItLooked() {
        assertThatThrownBy(() -> AgentRegistry.fromFolder(MAPPER, dir.resolve("nope")))
                .hasMessageContaining("agents folder not found").hasMessageContaining("AGENTS_DIR");
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
    }
}
