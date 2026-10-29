package com.emailagent.orchestrator;

import com.emailagent.config.AppConfig;
import com.emailagent.config.SendMode;
import com.emailagent.model.EmailStatus;
import com.emailagent.model.OutboxKind;
import com.emailagent.model.SlotType;
import com.emailagent.store.DraftEntity;
import com.emailagent.store.OutboxEntity;
import com.emailagent.store.ReviewEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * REVIEWED → SENT or DRAFT_SAVED (design doc §9, send modes). Plain code, no model: whether a reply leaves without
 * the owner is policy. Any doubt makes it a draft, never a refusal: the owner still gets the reply, one click away.
 * The reply goes into the outbox in the same transaction as the status; the relay hands it to the mailbox after.
 */
@ApplicationScoped
public class SendDispatcher implements AgentStep {

    /** The send policy: global mode, the types proven reliable, and the rate limits. */
    public record Policy(SendMode mode, Set<SlotType> autoTypes, int perSenderPerDay, int perDay) {}

    /** What happens to the reply, and every reason it isn't sent automatically. */
    public record Decision(OutboxKind kind, List<String> reasons) {}

    private final Policy policy;
    private final String domain;

    @Inject
    public SendDispatcher(AppConfig config) {
        this(new Policy(config.sendMode(), config.autoSendTypes().orElse(Set.of()),
                config.maxAutoRepliesPerSenderPerDay(), config.maxAutoRepliesPerDay()), config.ownerAddress());
    }

    public SendDispatcher(Policy policy, String ownerAddress) {
        this.policy = policy;
        this.domain = ownerAddress.substring(ownerAddress.indexOf('@') + 1);
    }

    @Override
    public String name() {
        return "dispatcher";
    }

    @Override
    public EmailStatus trigger() {
        return EmailStatus.REVIEWED;
    }

    private record Loaded(Long draftId, Long reviewId, boolean reviewMatches, List<String> outliers,
            long sentToSender, long sentTotal) {}

    @Override
    public StepResult run(EmailSnapshot email) {
        Loaded loaded = QuarkusTransaction.requiringNew().call(() -> {
            DraftEntity draft = DraftEntity.currentFor(email.id()).orElse(null);
            ReviewEntity review = ReviewEntity.latestFor(email.id()).orElse(null);
            boolean matches = draft != null && review != null && review.passed && review.draft.id.equals(draft.id);
            List<String> outliers = review == null || review.outlierReasons == null ? List.of()
                    : List.of(review.outlierReasons.split(","));
            long[] sent = sentInLastDay(email.sender());
            return new Loaded(draft == null ? null : draft.id, review == null ? null : review.id, matches, outliers,
                    sent[0], sent[1]);
        });
        if (!loaded.reviewMatches()) {
            // the draft changed after its review (or the review is missing): nothing unreviewed leaves
            return StepResult.to(EmailStatus.ESCALATED, "{\"escalated\":\"review_mismatch\"}");
        }
        Decision decision = decide(policy, email, loaded.outliers(), loaded.sentToSender(), loaded.sentTotal());
        boolean send = decision.kind() == OutboxKind.SEND;
        String output = "{\"mode\":\"" + decision.kind().name().toLowerCase(Locale.ROOT) + "\",\"reasons\":"
                + decision.reasons().stream().map(r -> "\"" + r + "\"").collect(Collectors.joining(",", "[", "]"))
                + "}";
        String messageId = "<reply-" + email.id() + "-" + loaded.draftId() + "-"
                + decision.kind().name().toLowerCase(Locale.ROOT) + "@" + domain + ">";
        return StepResult.to(send ? EmailStatus.SENT : EmailStatus.DRAFT_SAVED, output).withWrites(e -> {
            DraftEntity draft = DraftEntity.findById(loaded.draftId());
            OutboxEntity o = new OutboxEntity();
            o.email = e;
            o.draft = draft;
            o.review = ReviewEntity.findById(loaded.reviewId());
            o.kind = decision.kind();
            o.recipient = e.sender; // never from the draft or the model: the thread's own sender
            o.subject = replySubject(e.subject);
            o.body = draft.body;
            o.inReplyTo = e.messageId;
            o.messageId = messageId;
            o.createdAt = Instant.now();
            o.persist();
        });
    }

    /** Every condition for sending without the owner; each one that fails is a reason, and any reason means draft. */
    public static Decision decide(Policy policy, EmailSnapshot email, List<String> outliers, long sentToSender,
            long sentTotal) {
        List<String> reasons = new ArrayList<>();
        if (policy.mode() != SendMode.AUTO) {
            reasons.add("send_mode_draft");
        }
        if (email.slots().isEmpty() || email.slots().stream().anyMatch(s -> !s.type().shareable())) {
            reasons.add("personal_type"); // whatever the configuration says
        } else if (email.slots().stream().anyMatch(s -> !policy.autoTypes().contains(s.type()))) {
            reasons.add("type_not_auto");
        }
        if (email.scope() == null || !email.scope().isKnown()) {
            reasons.add("unknown_sender");
        }
        if (!outliers.isEmpty()) {
            reasons.add("outlier");
        }
        if (sentToSender >= policy.perSenderPerDay()) {
            reasons.add("rate_limit_sender");
        }
        if (sentTotal >= policy.perDay()) {
            reasons.add("rate_limit_daily");
        }
        return new Decision(reasons.isEmpty() ? OutboxKind.SEND : OutboxKind.DRAFT, List.copyOf(reasons));
    }

    /**
     * A clarification or holding reply: template text, so no review, but the same mode and rate limits. Sent only
     * when notices may be automatic and the global mode is auto.
     */
    public static OutboxKind noticeKind(Policy policy, boolean noticeAutoSend, long sentToSender, long sentTotal) {
        boolean send = noticeAutoSend && policy.mode() == SendMode.AUTO && sentToSender < policy.perSenderPerDay()
                && sentTotal < policy.perDay();
        return send ? OutboxKind.SEND : OutboxKind.DRAFT;
    }

    /** Automatic sends in the last 24 hours: to this recipient, and in total. Call inside a transaction. */
    static long[] sentInLastDay(String recipient) {
        Instant since = Instant.now().minus(Duration.ofDays(1));
        return new long[] {
            OutboxEntity.count("kind = ?1 and lower(recipient) = ?2 and createdAt > ?3", OutboxKind.SEND,
                    recipient.toLowerCase(Locale.ROOT), since),
            OutboxEntity.count("kind = ?1 and createdAt > ?2", OutboxKind.SEND, since)
        };
    }

    Policy policy() {
        return policy;
    }

    String domain() {
        return domain;
    }

    /** "Re: " + the original subject on one line: the sender wrote it, so no line break may reach the headers. */
    static String replySubject(String subject) {
        String clean = subject == null ? "" : subject.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ")
                .replaceAll("\\s+", " ").strip();
        String reply = clean.regionMatches(true, 0, "re:", 0, 3) ? clean : ("Re: " + clean).strip();
        return reply.length() > 200 ? reply.substring(0, 200) : reply;
    }
}
