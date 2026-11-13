package com.emailagent.web;

import com.emailagent.agents.GroupingAgent;
import com.emailagent.model.ClusterStatus;
import com.emailagent.store.AgentDefinition;
import com.emailagent.store.AgentRegistry;
import com.emailagent.store.EmailEntity;
import com.emailagent.store.FactEntity;
import com.emailagent.store.InputRequestEntity;
import com.emailagent.store.KnowledgeSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Locale;

/**
 * The agents page (brief §10: view the agents, their prompt and their context): what each specialized agent is, what
 * routes an email to it, what it may read, and how much it has handled. Read-only: agents are files the owner edits.
 */
@ApplicationScoped
public class AgentQueries {

    /** One entry of an agent's knowledge base or calendar, as the owner wrote it. */
    public record ContextEntry(String label, String entity, String value, String scope, String source) {}

    public record AgentView(String id, String name, String description, int order, boolean fallback,
            boolean membersOnly, List<String> roles, List<String> addresses, List<String> domains,
            List<String> keywords, List<String> members, String promptFile, String prompt,
            List<String> files, List<ContextEntry> knowledge, List<ContextEntry> calendar,
            long emails, long facts, long openCards) {}

    public record Page(List<AgentView> agents, String folder, long unrouted) {}

    private final AgentRegistry registry;
    private final String folder;

    public AgentQueries(AgentRegistry registry, com.emailagent.config.AppConfig config) {
        this.registry = registry;
        this.folder = config.agentsDir();
    }

    public Page page() {
        return QuarkusTransaction.requiringNew().call(() -> new Page(
                registry.all().stream().map(AgentQueries::view).toList(), folder,
                EmailEntity.count("agentId is null")));
    }

    private static AgentView view(AgentRegistry.Agent a) {
        AgentDefinition d = a.definition();
        AgentDefinition.Routing r = d.routing();
        List<String> files = new java.util.ArrayList<>();
        files.add(d.id() + ".json");
        if (d.promptFile() != null) {
            files.add(d.promptFile());
        }
        files.addAll(d.knowledge());
        files.addAll(d.calendar());
        return new AgentView(d.id(), d.name(), d.description(), d.order(), d.fallback(), r.membersOnly(),
                r.senderRoles().stream().map(x -> x.name().toLowerCase(Locale.ROOT)).toList(), r.senderAddresses(),
                r.senderDomains(), r.keywords(), r.members(), d.promptFile(), a.prompt(), files,
                entries(a.knowledge()), entries(a.calendar()),
                EmailEntity.count("agentId", d.id()),
                FactEntity.count("agentId = ?1 and active = true", d.id()),
                InputRequestEntity.count("agentId = ?1 and status in ?2", d.id(),
                        List.of(ClusterStatus.OPEN, ClusterStatus.WAITING_INPUT)));
    }

    private static List<ContextEntry> entries(KnowledgeSource source) {
        return source.entries().stream().map(e -> new ContextEntry(GroupingAgent.label(e.slotType()), e.entity()
                        + (e.qualifier() == null ? "" : " (" + e.qualifier() + ")"), e.value(),
                        e.program() == null ? "everyone" : e.program() + " " + e.track() + " " + e.year(),
                        e.source() != null ? e.source() : e.title()))
                .toList();
    }
}
