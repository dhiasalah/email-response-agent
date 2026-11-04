package com.emailagent.web;

import com.emailagent.agents.CardPriority;
import com.emailagent.agents.GroupingAgent;
import com.emailagent.model.EmailStatus;
import com.emailagent.model.FieldType;
import com.emailagent.model.OutboxKind;
import com.emailagent.model.SlotType;
import com.emailagent.store.DecisionLogEntry;
import com.emailagent.store.EmailEntity;
import com.emailagent.store.FactEntity;
import com.emailagent.store.OutboxEntity;
import com.emailagent.store.RecentAnswerEntity;
import com.emailagent.store.SlotEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The owner's two read-only views next to the queue: what the agent knows (design doc §8.2 "What the agent knows",
 * §10 memory view) and what it did recently (§12, from the audit log). Records of strings; the templates escape them.
 */
@ApplicationScoped
public class InsightQueries {

    static final int ACTIVITY_ROWS = 60;
    static final int OUTBOX_ROWS = 25;
    static final Duration RECENT_WINDOW = Duration.ofDays(7);
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault());

    /** {@code uses}: emails whose slot was filled from this fact (any version of the id). */
    public record FactView(long id, String label, String entity, String qualifier, String scope, String value,
            boolean shareable, String validUntil, boolean expired, int version, String source, long uses,
            boolean broadcast, String created) {}

    public record RecentView(String label, String entity, String scope, String value, String answered) {}

    public record Memory(List<FactView> active, List<FactView> past, List<RecentView> recent) {

        public long expiredCount() {
            return active.stream().filter(FactView::expired).count();
        }

        public long totalUses() {
            return active.stream().mapToLong(FactView::uses).sum();
        }
    }

    public record ActivityRow(StepText.Step step, Long emailId, String subject, String sender) {}

    public record SentView(String when, String recipient, String subject, String kind, boolean delivered) {

        public boolean isSend() {
            return "SEND".equals(kind);
        }
    }

    public record Activity(List<ActivityRow> rows, List<SentView> outbox, long sentToday, long escalatedToday) {}

    public Memory memory() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Instant now = Instant.now();
            Map<Long, Long> uses = new HashMap<>();
            for (SlotEntity s : SlotEntity.<SlotEntity>list("source like 'fact:%'")) {
                Long id = factId(s.source);
                if (id != null) {
                    uses.merge(id, 1L, Long::sum);
                }
            }
            List<FactView> active = FactEntity.<FactEntity>list("active = true order by createdAt desc").stream()
                    .map(f -> view(f, now, uses)).toList();
            List<FactView> past = FactEntity.<FactEntity>list("active = false order by createdAt desc")
                    .stream().limit(20).map(f -> view(f, now, uses)).toList();
            List<RecentView> recent = RecentAnswerEntity.<RecentAnswerEntity>list(
                            "supersededAt is null and answeredAt > ?1 order by answeredAt desc", now.minus(RECENT_WINDOW))
                    .stream().map(r -> new RecentView(GroupingAgent.label(r.slotType), r.entityKey,
                            scope(r.scopeProgram, r.scopeTrack, r.scopeYear), display(r.slotType, r.value),
                            DAY.format(r.answeredAt)))
                    .toList();
            return new Memory(active, past, recent);
        });
    }

    public Activity activity() {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<DecisionLogEntry> log = DecisionLogEntry.<DecisionLogEntry>find("order by id desc")
                    .page(0, ACTIVITY_ROWS).list();
            List<Long> ids = log.stream().map(d -> d.emailId).filter(id -> id != null).distinct().toList();
            Map<Long, EmailEntity> emails = ids.isEmpty() ? Map.of() : EmailEntity.<EmailEntity>list("id in ?1", ids)
                    .stream().collect(Collectors.toMap(e -> e.id, e -> e));
            List<ActivityRow> rows = log.stream().map(d -> {
                EmailEntity e = d.emailId == null ? null : emails.get(d.emailId);
                return new ActivityRow(StepText.of(d), d.emailId, e == null ? null : e.subject,
                        e == null ? null : (e.senderName != null && !e.senderName.isBlank() ? e.senderName : e.sender));
            }).toList();
            List<SentView> outbox = OutboxEntity.<OutboxEntity>find("order by id desc").page(0, OUTBOX_ROWS).list()
                    .stream().map(o -> new SentView(StepText.WHEN.format(o.createdAt), o.recipient, o.subject,
                            o.kind.name(), o.deliveredAt != null)).toList();
            Instant since = Instant.now().minus(Duration.ofDays(1));
            long sent = OutboxEntity.count("kind = ?1 and createdAt > ?2", OutboxKind.SEND, since);
            long escalated = DecisionLogEntry.count("toStatus = ?1 and createdAt > ?2", EmailStatus.ESCALATED, since);
            return new Activity(rows, outbox, sent, escalated);
        });
    }

    private static FactView view(FactEntity f, Instant now, Map<Long, Long> uses) {
        boolean expired = f.validUntil != null && f.validUntil.isBefore(now);
        return new FactView(f.id, GroupingAgent.label(f.slotType), f.entityKey, f.qualifierKey,
                scope(f.scopeProgram, f.scopeTrack, f.scopeYear), display(f.slotType, f.value), f.shareable,
                f.validUntil == null ? null : DAY.format(f.validUntil), expired, f.factVersion, f.source,
                uses.getOrDefault(f.id, 0L), f.broadcastAt != null, DAY.format(f.createdAt));
    }

    /** A stored date ("2027-06-18") as people write it ("18 June 2027"); any other value unchanged. */
    static String display(SlotType type, String value) {
        if (type == null || type.fieldType() != FieldType.DATE) {
            return value;
        }
        LocalDate d = CardPriority.parseDate(value);
        return d == null ? value : LONG_DAY.format(d);
    }

    static final DateTimeFormatter LONG_DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    /** "fact:12:v1" → 12; anything else → null. */
    static Long factId(String source) {
        if (source == null || !source.startsWith("fact:")) {
            return null;
        }
        String rest = source.substring(5);
        int colon = rest.indexOf(':');
        try {
            return Long.parseLong(colon < 0 ? rest : rest.substring(0, colon));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String scope(String program, String track, String year) {
        return String.join(" ", List.of(program, track, year).stream().filter(s -> s != null && !s.isBlank())
                .toList());
    }
}
