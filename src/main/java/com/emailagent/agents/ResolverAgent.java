package com.emailagent.agents;

import com.emailagent.config.AppConfig;
import com.emailagent.model.EmailStatus;
import com.emailagent.model.SenderScope;
import com.emailagent.model.SlotKey;
import com.emailagent.model.SlotOwner;
import com.emailagent.model.SlotStatus;
import com.emailagent.model.SlotType;
import com.emailagent.step.AgentStep;
import com.emailagent.step.EmailSnapshot;
import com.emailagent.step.StepResult;
import com.emailagent.store.FactEntity;
import com.emailagent.store.KnowledgeBase;
import com.emailagent.store.KnowledgeSource;
import com.emailagent.store.MockCalendar;
import com.emailagent.store.RecentAnswerEntity;
import com.emailagent.store.SlotEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RESOLVING → READY, AWAITING_INPUT or AWAITING_SENDER (design doc §4.2, §8, §16.4). Plain code, no model:
 * deciding which stored answer a sender may receive is an access decision, and an access decision must not be
 * something an email can argue with. The model only chose the slot key (triage); the scope comes from the roster.
 */
@ApplicationScoped
public class ResolverAgent implements AgentStep {

    /** How one slot was resolved; {@code status} UNRESOLVED means nobody had the answer. */
    public record Resolution(long slotId, SlotStatus status, String value, String source) {}

    private final KnowledgeBase knowledgeBase;
    private final MockCalendar calendar;
    private final Duration freshness;
    private final Duration window;
    private final Set<SlotType> trustedRecentTypes;

    public ResolverAgent(KnowledgeBase knowledgeBase, MockCalendar calendar, AppConfig config) {
        this.knowledgeBase = knowledgeBase;
        this.calendar = calendar;
        this.freshness = Duration.ofDays(config.factFreshnessDays());
        this.window = Duration.ofDays(config.recentAnswerWindowDays());
        this.trustedRecentTypes = config.recentAnswerTrustedTypes().orElse(Set.of());
    }

    @Override
    public String name() {
        return "resolver";
    }

    @Override
    public EmailStatus trigger() {
        return EmailStatus.RESOLVING;
    }

    @Override
    public StepResult run(EmailSnapshot email) {
        if (email.slots().isEmpty()) {
            return StepResult.to(EmailStatus.ESCALATED, "{\"resolved\":\"no_slots\"}"); // fail closed
        }
        Instant now = Instant.now();
        // read-only lookups in a short transaction of their own; all writes come back in the StepResult
        List<Resolution> resolutions = QuarkusTransaction.requiringNew().call(() -> email.slots().stream()
                .map(slot -> slot.status() == SlotStatus.UNRESOLVED
                        ? resolve(slot, email.scope(), now)
                        : new Resolution(slot.id(), slot.status(), slot.value(), slot.source()))
                .toList());

        EmailStatus next = nextStatus(email, resolutions);
        Map<Long, Resolution> byId = new LinkedHashMap<>();
        resolutions.forEach(r -> byId.put(r.slotId(), r));
        return StepResult.to(next, log(resolutions)).withWrites(e -> {
            for (SlotEntity slot : e.slots) {
                Resolution r = byId.get(slot.id);
                if (r != null && r.status() != SlotStatus.UNRESOLVED && slot.status == SlotStatus.UNRESOLVED) {
                    slot.fill(r.value(), r.source(), r.status());
                }
            }
        });
    }

    /** Fixed order: confirmed memory, knowledge base, calendar, recent answers. First trusted match wins. */
    Resolution resolve(EmailSnapshot.Slot slot, SenderScope scope, Instant now) {
        long id = slot.id();
        if (!slot.type().shareable()) {
            // meetings, individual decisions: only the owner decides, for this sender only
            return new Resolution(id, SlotStatus.UNRESOLVED, null, null);
        }
        SlotKey key = new SlotKey(slot.type(), slot.entity(), slot.qualifier());

        if (scope.isKnown()) {
            Optional<FactEntity> fact = FactEntity.findActive(key, scope)
                    .filter(f -> f.shareable)
                    .filter(f -> f.validUntil == null || f.validUntil.isAfter(now));
            if (fact.isPresent()) {
                FactEntity f = fact.get();
                boolean fresh = f.createdAt.isAfter(now.minus(freshness));
                return new Resolution(id, fresh ? SlotStatus.FILLED : SlotStatus.PROPOSED, f.value,
                        "fact:" + f.id + ":v" + f.factVersion);
            }
        }

        Optional<KnowledgeSource.Entry> kb = knowledgeBase.find(key, scope);
        if (kb.isPresent()) {
            return new Resolution(id, SlotStatus.FILLED, kb.get().value(), "kb:" + kb.get().id() + " " + kb.get().source());
        }

        Optional<KnowledgeSource.Entry> event = calendar.find(key, scope);
        if (event.isPresent()) {
            KnowledgeSource.Entry ev = event.get();
            return new Resolution(id, SlotStatus.PROPOSED, ev.value(),
                    "calendar:" + ev.id() + " " + ev.title() + " (" + ev.date() + ")");
        }

        if (scope.isKnown()) {
            List<RecentAnswerEntity> recent = RecentAnswerEntity.find(key, scope, now.minus(window));
            if (!recent.isEmpty()) {
                return fromRecent(id, slot.type(), recent);
            }
        }
        return new Resolution(id, SlotStatus.UNRESOLVED, null, null);
    }

    /** One answer → proposed. Same answer repeated, no conflict, trusted type → filled. Conflict → proposed. */
    private Resolution fromRecent(long id, SlotType type, List<RecentAnswerEntity> recent) {
        Map<String, List<RecentAnswerEntity>> byValue = recent.stream()
                .collect(Collectors.groupingBy(r -> SlotKey.normalize(r.value), LinkedHashMap::new, Collectors.toList()));
        String ids = recent.stream().map(r -> String.valueOf(r.id)).collect(Collectors.joining(","));
        RecentAnswerEntity newest = recent.get(0);
        if (byValue.size() > 1) {
            return new Resolution(id, SlotStatus.PROPOSED, newest.value,
                    "recent:" + ids + " conflict: " + byValue.size() + " different answers");
        }
        boolean repeated = recent.size() >= 2;
        SlotStatus status = repeated && trustedRecentTypes.contains(type) ? SlotStatus.FILLED : SlotStatus.PROPOSED;
        return new Resolution(id, status, newest.value, "recent:" + ids);
    }

    /** Join rule (design doc §6): READY only when every slot is filled. Sender-owned gaps ask the sender first. */
    static EmailStatus nextStatus(EmailSnapshot email, List<Resolution> resolutions) {
        if (resolutions.stream().allMatch(r -> r.status() == SlotStatus.FILLED)) {
            return EmailStatus.READY;
        }
        Map<Long, SlotOwner> owners = new LinkedHashMap<>();
        email.slots().forEach(s -> owners.put(s.id(), s.owner()));
        boolean senderMustAnswer = resolutions.stream()
                .anyMatch(r -> r.status() == SlotStatus.UNRESOLVED && owners.get(r.slotId()) == SlotOwner.SENDER);
        return senderMustAnswer ? EmailStatus.AWAITING_SENDER : EmailStatus.AWAITING_INPUT;
    }

    /** Audit: per slot, the outcome and where it came from; values stay in the slot rows. */
    private static String log(List<Resolution> resolutions) {
        List<String> parts = new ArrayList<>();
        for (Resolution r : resolutions) {
            String origin = r.source() == null ? "none" : r.source().split("[: ]")[0];
            parts.add("{\"slot\":" + r.slotId() + ",\"status\":\"" + r.status() + "\",\"from\":\"" + origin + "\"}");
        }
        return "{\"slots\":[" + String.join(",", parts) + "]}";
    }
}
