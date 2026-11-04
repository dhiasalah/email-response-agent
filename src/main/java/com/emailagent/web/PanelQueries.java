package com.emailagent.web;

import com.emailagent.agents.CardPriority;
import com.emailagent.orchestrator.OwnerActions;
import java.time.LocalDate;

import com.emailagent.agents.GroupingAgent;
import com.emailagent.model.EmailStatus;
import com.emailagent.model.NoticeKind;
import com.emailagent.orchestrator.OwnerActions;
import com.emailagent.store.DecisionLogEntry;
import com.emailagent.store.DraftEntity;
import com.emailagent.store.EmailEntity;
import com.emailagent.store.InputRequestEntity;
import com.emailagent.store.NoticeEntity;
import com.emailagent.store.ReviewEntity;
import com.emailagent.store.SlotEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What the panel shows (design doc §10): cards to answer, drafts to approve grouped by card, escalations, broadcasts.
 * Plain records of strings, read in one transaction; the templates escape every one of them.
 */
@ApplicationScoped
public class PanelQueries {

    static final int EXCERPT = 600;

    /** An email waiting on a card: who asked, and the question in their words (design doc §7.3). */
    public record LinkedEmail(String sender, String senderName, String subject, String question) {}

    public record CardView(long id, long version, String label, String question, String audience, boolean personal,
            int members, String priority, String proposedValue, String proposalSource, String fieldType,
            String isoProposal, List<LinkedEmail> emails) {

        public boolean isDate() {
            return "DATE".equals(fieldType);
        }

        public boolean isYesNo() {
            return "YES_NO".equals(fieldType);
        }

        public boolean isLongText() {
            return "TEXT".equals(fieldType);
        }

        /** The pre-checked yes/no button: the proposal's, if it is one. */
        public boolean proposesYes() {
            return proposedValue != null && proposedValue.strip().equalsIgnoreCase("yes");
        }

        public boolean proposesNo() {
            return proposedValue != null && proposedValue.strip().equalsIgnoreCase("no");
        }
    }

    public record DraftView(long emailId, String sender, String subject, String body, String language,
            String factsUsed, List<String> outliers) {

        public boolean isOutlier() {
            return !outliers.isEmpty();
        }
    }

    /** One card's drafts: the representative in full, the others compactly, outliers apart (design doc §7.6). */
    public record DraftGroup(Long cardId, String title, DraftView representative, List<DraftView> others,
            List<DraftView> outliers) {

        /** What "approve all" sends: the representative and the others, never the outliers. */
        public int sendable() {
            return (representative == null ? 0 : 1) + others.size();
        }
    }

    /**
     * {@code draft}: the rejected or stopped reply, if there is one, for the owner to edit and send. {@code spoofed}:
     * the display name uses look-alike letters, so the panel warns before the owner trusts the name.
     */
    public record EscalationView(long emailId, String sender, String senderName, String subject, String excerpt,
            ReasonText.Reason reason, String draft, boolean canRetry, boolean spoofed, String language) {}

    public record BroadcastView(long id, String recipient, String subject, String body) {}

    public record Dashboard(List<CardView> cards, List<DraftGroup> drafts, List<EscalationView> escalations,
            List<BroadcastView> broadcasts) {

        /** Every saved draft, not groups: what the owner has to read. */
        public int draftCount() {
            return drafts.stream().mapToInt(g -> g.sendable() + g.outliers().size()).sum();
        }
    }

    public Dashboard dashboard() {
        return QuarkusTransaction.requiringNew().call(() -> new Dashboard(cards(), draftGroups(), escalations(),
                broadcasts()));
    }

    /** The emails of a card whose review flagged them: never part of "approve all". */
    public List<Long> outliersOf(long cardId) {
        return QuarkusTransaction.requiringNew().call(() -> draftsOfCard(cardId).stream()
                .filter(DraftView::isOutlier).map(DraftView::emailId).toList());
    }

    private static List<CardView> cards() {
        return InputRequestEntity.listQueue().stream().map(c -> new CardView(c.id, c.version,
                GroupingAgent.label(c.slotType), c.question,
                c.personal ? "this sender only" : c.scopeProgram + " " + c.scopeTrack + " " + c.scopeYear,
                c.personal, c.memberCount, String.format("%.2f", c.priority), c.proposedValue, c.proposalSource,
                c.fieldType == null ? "TEXT" : c.fieldType.name(), isoDate(c.proposedValue), linkedEmails(c.id)))
                .toList();
    }

    /** The emails still waiting on the card, oldest first: the owner sees whom the answer goes to. */
    private static List<LinkedEmail> linkedEmails(long cardId) {
        return SlotEntity.<SlotEntity>list("requestId = ?1 and email.status = ?2 order by email.receivedAt, id",
                cardId, EmailStatus.AWAITING_INPUT).stream()
                .map(s -> new LinkedEmail(s.email.sender, s.email.senderName, s.email.subject,
                        s.question == null ? "" : excerpt(s.question)))
                .toList();
    }

    /** "18 June 2027" → "2027-06-18" for the date picker; null when it isn't a date. */
    static String isoDate(String value) {
        LocalDate d = CardPriority.parseDate(value);
        return d == null ? null : d.toString();
    }

    private static List<DraftGroup> draftGroups() {
        Map<Long, List<DraftView>> byCard = new LinkedHashMap<>();
        List<DraftGroup> groups = new ArrayList<>();
        for (EmailEntity e : EmailEntity.<EmailEntity>list("status = ?1 order by receivedAt, id",
                EmailStatus.DRAFT_SAVED)) {
            DraftView view = draftView(e);
            if (view == null) {
                continue;
            }
            Long card = SlotEntity.<SlotEntity>list("email.id = ?1 and requestId is not null", e.id).stream()
                    .map(s -> s.requestId).findFirst().orElse(null);
            if (card == null) {
                groups.add(group(null, "Single reply", List.of(view)));
            } else {
                byCard.computeIfAbsent(card, k -> new ArrayList<>()).add(view);
            }
        }
        byCard.forEach((card, views) -> {
            InputRequestEntity c = InputRequestEntity.findById(card);
            groups.add(group(card, c == null ? "Card " + card : GroupingAgent.label(c.slotType) + ": " + c.question,
                    views));
        });
        return groups;
    }

    private static List<DraftView> draftsOfCard(long cardId) {
        return SlotEntity.<SlotEntity>list("requestId = ?1 and email.status = ?2", cardId, EmailStatus.DRAFT_SAVED)
                .stream().map(s -> s.email).distinct().map(PanelQueries::draftView).filter(v -> v != null).toList();
    }

    private static DraftView draftView(EmailEntity e) {
        Optional<DraftEntity> d = DraftEntity.currentFor(e.id);
        if (d.isEmpty()) {
            return null;
        }
        String outliers = ReviewEntity.latestFor(e.id).map(r -> r.outlierReasons).orElse(null);
        return new DraftView(e.id, e.sender, e.subject, d.get().body, d.get().language.name(), d.get().factsUsed,
                outliers == null ? List.of() : List.of(outliers.split(",")));
    }

    static DraftGroup group(Long cardId, String title, List<DraftView> views) {
        List<DraftView> outliers = views.stream().filter(DraftView::isOutlier).toList();
        List<DraftView> normal = views.stream().filter(v -> !v.isOutlier()).toList();
        DraftView rep = representative(normal).orElse(null);
        List<DraftView> others = normal.stream().filter(v -> v != rep).toList();
        return new DraftGroup(cardId, title, rep, others, outliers);
    }

    /**
     * The most typical reply (design doc §7.6): the majority language, then the length closest to the group's median.
     * Code, not a model: the choice must be explainable and stable.
     */
    static Optional<DraftView> representative(List<DraftView> views) {
        if (views.isEmpty()) {
            return Optional.empty();
        }
        String language = views.stream().collect(Collectors.groupingBy(DraftView::language, LinkedHashMap::new,
                Collectors.counting())).entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        List<DraftView> same = views.stream().filter(v -> v.language().equals(language)).toList();
        List<Integer> lengths = same.stream().map(v -> v.body().length()).sorted().toList();
        int median = lengths.get(lengths.size() / 2);
        Function<DraftView, Integer> distance = v -> Math.abs(v.body().length() - median);
        return same.stream().min(Comparator.comparing(distance).thenComparing(DraftView::emailId));
    }

    private static List<EscalationView> escalations() {
        return EmailEntity.<EmailEntity>list("status = ?1 order by receivedAt, id", EmailStatus.ESCALATED).stream()
                .map(e -> new EscalationView(e.id, e.sender, e.senderName, e.subject, excerpt(e.body), reason(e.id),
                        DraftEntity.currentFor(e.id).map(d -> d.body).orElse(null), OwnerActions.canRetry(e.id),
                        e.injectionSignals != null && e.injectionSignals.contains("name_mixed_scripts"),
                        e.language == null ? "EN" : e.language.name()))
                .toList();
    }

    /** Our own codes from the audit row ("sensitive_keyword", "unsupported_link") in words: never the model's. */
    private static ReasonText.Reason reason(long emailId) {
        List<DecisionLogEntry> rows = DecisionLogEntry.list(
                "emailId = ?1 and toStatus = ?2 order by id desc", emailId, EmailStatus.ESCALATED);
        return rows.isEmpty() ? ReasonText.of(null, null) : ReasonText.of(rows.get(0).agent, rows.get(0).output);
    }

    private static List<BroadcastView> broadcasts() {
        return NoticeEntity.<NoticeEntity>list("kind = ?1 and approvedAt is null order by id", NoticeKind.BROADCAST)
                .stream().map(n -> new BroadcastView(n.id, n.recipient, n.subject, n.body)).toList();
    }

    private static String excerpt(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > EXCERPT ? body.substring(0, EXCERPT) + " […]" : body;
    }
}
