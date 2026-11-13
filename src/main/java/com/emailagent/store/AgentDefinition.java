package com.emailagent.store;

import com.emailagent.model.SenderRole;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * One specialized agent as the owner writes it: {@code agents/<id>.json} (brief §4.2, §5). Data, not code: adding an
 * agent, changing its prompt or its context files means editing files, never Java. Unknown keys are refused, so a
 * typo stops the start-up instead of being silently ignored.
 *
 * @param id          the file name without {@code .json}; stored on every email, fact and card of this agent
 * @param name        shown to the owner
 * @param description what kind of email this agent handles, in one sentence (shown in the panel)
 * @param order       tie-break between agents that fit equally well: lower first
 * @param fallback    the agent that takes an email no other agent fits; exactly one agent has it
 * @param routing     the signals the router looks for (sender, role, words)
 * @param promptFile  the agent's system prompt, a text file next to the definition
 * @param knowledge   knowledge-base files (JSON entries that fill a slot)
 * @param calendar    calendar files (JSON events that propose a value for the owner to confirm)
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AgentDefinition(String id, String name, String description, Integer order, Boolean fallback,
        Routing routing, String promptFile, List<String> knowledge, List<String> calendar) {

    /**
     * What makes an email fit this agent. Addresses and domains count only for an authenticated sender; keywords
     * are matched in the subject and body, without case or accents.
     *
     * @param senderAddresses senders whose email is strong evidence for this agent
     * @param members         if not empty, the agent is members-only: it never takes an email from an (authenticated)
     *                        sender outside this list, however well the words match. Its context is for its members
     *                        (a research project), and naming the project must not be enough to reach it. Being a
     *                        member is not evidence by itself: a member writing about something else goes elsewhere.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Routing(List<SenderRole> senderRoles, List<String> senderAddresses, List<String> senderDomains,
            List<String> keywords, List<String> members) {

        public static final Routing NONE = new Routing(List.of(), List.of(), List.of(), List.of(), List.of());

        public Routing {
            senderRoles = senderRoles == null ? List.of() : List.copyOf(senderRoles);
            senderAddresses = senderAddresses == null ? List.of() : List.copyOf(senderAddresses);
            senderDomains = senderDomains == null ? List.of() : List.copyOf(senderDomains);
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
            members = members == null ? List.of() : List.copyOf(members);
        }

        public boolean membersOnly() {
            return !members.isEmpty();
        }
    }

    public AgentDefinition {
        order = order == null ? 100 : order;
        fallback = fallback != null && fallback;
        routing = routing == null ? Routing.NONE : routing;
        knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
        calendar = calendar == null ? List.of() : List.copyOf(calendar);
    }
}
