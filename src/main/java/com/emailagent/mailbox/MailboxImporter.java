package com.emailagent.mailbox;

import com.emailagent.agents.CuratorAgent;
import com.emailagent.config.AppConfig;
import com.emailagent.model.EmailStatus;
import com.emailagent.model.FilterReason;
import com.emailagent.orchestrator.Retention;
import com.emailagent.security.PreFilter;
import com.emailagent.security.Sanitizer;
import com.emailagent.security.SenderAuthentication;
import com.emailagent.store.DecisionLogEntry;
import com.emailagent.store.EmailEntity;
import com.emailagent.store.NoticeEntity;
import com.emailagent.store.OutboxEntity;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Moves new mail into the shared state: skip duplicates, pre-filter, sanitize, store.
 * One transaction per email, so one broken message can't block the others (availability).
 */
@ApplicationScoped
public class MailboxImporter {

    public record ImportResult(int imported, int filtered, int duplicates, int rejected, int merged) {}

    private final Mailbox mailbox;
    private final PreFilter preFilter;
    private final Sanitizer sanitizer;
    private final boolean senderAuthRequired;
    private final String trustedAuthServId;

    public MailboxImporter(Mailbox mailbox, PreFilter preFilter, Sanitizer sanitizer, AppConfig config) {
        this.mailbox = mailbox;
        this.preFilter = preFilter;
        this.sanitizer = sanitizer;
        this.senderAuthRequired = senderAuthRequired(config.requireSenderAuth(), mailbox.authenticatesSenders());
        this.trustedAuthServId = config.trustedAuthservId();
    }

    /**
     * Unset: required exactly when the mailbox running carries real mail. The mailbox bean is chosen when the
     * application is built, so the running bean decides, not a MAILBOX variable that may differ at start.
     */
    static boolean senderAuthRequired(Optional<Boolean> configured, boolean realMailbox) {
        return configured.orElse(realMailbox);
    }

    /**
     * The flag for a sender the receiving server didn't authenticate, when the mailbox requires it (design doc §9):
     * the email is still imported and shown to the owner, but its From address earns no trust.
     */
    static Optional<String> authFlag(IncomingEmail in, boolean required, String authServId) {
        return required && !SenderAuthentication.isAuthenticated(in, authServId)
                ? Optional.of(SenderAuthentication.UNVERIFIED_FLAG) : Optional.empty();
    }

    public ImportResult importNew() {
        return importNew(mailbox.fetch());
    }

    /** Imports the given messages (the mailbox's, or a test's), each in its own transaction. */
    public ImportResult importNew(List<IncomingEmail> messages) {
        int imported = 0;
        int filtered = 0;
        int duplicates = 0;
        int rejected = 0;
        int merged = 0;
        for (IncomingEmail in : messages) {
            try {
                Outcome outcome = QuarkusTransaction.requiringNew().call(() -> importOne(in));
                switch (outcome) {
                    case IMPORTED -> imported++;
                    case FILTERED -> filtered++;
                    case DUPLICATE -> duplicates++;
                    case MERGED -> merged++;
                }
            } catch (RuntimeException e) {
                // never log the content; the exception type is enough to investigate with the message id
                Log.warnf("rejected message %s (%s)", in.messageId(), e.getClass().getSimpleName());
                rejected++;
            }
        }
        return new ImportResult(imported, filtered, duplicates, rejected, merged);
    }

    private enum Outcome { IMPORTED, FILTERED, DUPLICATE, MERGED }

    private Outcome importOne(IncomingEmail in) {
        if (EmailEntity.findByMessageId(in.messageId()).isPresent()
                || EmailEntity.findByMessageId(Retention.erasedMessageId(in.messageId())).isPresent()) {
            // an erased email still in the mailbox is not imported again
            return Outcome.DUPLICATE; // idempotence: the same message is never processed twice
        }
        long recent = EmailEntity.count("sender = ?1 and receivedAt > ?2 and receivedAt <= ?3",
                in.from(), in.receivedAt().minus(Duration.ofHours(1)), in.receivedAt());
        Optional<FilterReason> reason = preFilter.check(in, recent);

        EmailEntity email = new EmailEntity();
        email.messageId = in.messageId();
        email.threadId = in.threadId();
        email.sender = in.from();
        email.receivedAt = in.receivedAt();
        Sanitizer.Sanitized name = sanitizer.cleanLine(in.fromName(), 200);
        Sanitizer.Sanitized subject = sanitizer.cleanLine(in.subject(), 998);
        email.senderName = name.text();
        email.subject = subject.text();

        if (reason.isPresent()) {
            // data minimization: a filtered email keeps its metadata, not its body
            email.status = EmailStatus.FILTERED_OUT;
            email.filterReason = reason.get();
            email.persist();
            DecisionLogEntry.record(email.id, "prefilter", "message " + email.id, "{\"filtered\":\"" + reason.get() + "\"}");
            return Outcome.FILTERED;
        }

        Sanitizer.Sanitized body = sanitizer.clean(in.bodyHtml(), in.bodyText());
        Set<String> flags = new TreeSet<>(body.flags());
        name.flags().forEach(f -> flags.add("name_" + f));
        subject.flags().forEach(f -> flags.add("subject_" + f));
        // before the merge: a forged answer to a clarification also marks the email it joins
        authFlag(in, senderAuthRequired, trustedAuthServId).ifPresent(flags::add);

        Optional<EmailEntity> answered = answeredEmail(in);
        if (answered.isPresent()) {
            merge(answered.get(), email, body.text(), flags);
            return Outcome.MERGED;
        }

        email.body = body.text();
        email.sanitizerFlags = flags.isEmpty() ? null : String.join(",", flags);
        email.persist();
        DecisionLogEntry.record(email.id, "prefilter", "message " + email.id,
                "{\"filtered\":null,\"flags\":\"" + (email.sanitizerFlags == null ? "" : email.sanitizerFlags) + "\"}");
        return Outcome.IMPORTED;
    }

    /**
     * The email this message answers, if it is the sender's reply to a clarification (design doc §6 thread rule):
     * same sender address, exactly, and the same thread or a reply to one of our questions.
     */
    private static Optional<EmailEntity> answeredEmail(IncomingEmail in) {
        String inReplyTo = in.header("In-Reply-To");
        List<EmailEntity> waiting = EmailEntity.list("status = ?1 and lower(sender) = ?2 order by receivedAt desc",
                EmailStatus.AWAITING_SENDER, in.from().toLowerCase(Locale.ROOT));
        return waiting.stream().filter(e -> (e.threadId != null && e.threadId.equals(in.threadId()))
                        || (inReplyTo != null && OutboxEntity.count("email.id = ?1 and notice is not null"
                                + " and messageId = ?2", e.id, inReplyTo.strip()) > 0))
                .findFirst();
    }

    /**
     * Adds the sender's new words (quoted text cut) to the waiting email, marks the question answered, and keeps the
     * reply itself as a filtered row without body, so it is never imported twice. Triage runs again later.
     */
    private static void merge(EmailEntity target, EmailEntity reply, String text, Set<String> flags) {
        String own = CuratorAgent.ownerText(text);
        String merged = (target.body == null ? "" : target.body) + "\n\n[Reply of the sender]\n" + own;
        target.body = merged.length() > MAX_BODY ? merged.substring(0, MAX_BODY) : merged;
        if (!flags.isEmpty()) {
            Set<String> all = new TreeSet<>(flags);
            if (target.sanitizerFlags != null) {
                all.addAll(List.of(target.sanitizerFlags.split(",")));
            }
            String csv = String.join(",", all);
            target.sanitizerFlags = csv.length() > 500 ? csv.substring(0, 500) : csv;
        }
        target.senderReplied = true;
        NoticeEntity.latestClarification(target.id).filter(n -> n.answeredAt == null)
                .ifPresent(n -> n.answeredAt = Instant.now());

        reply.status = EmailStatus.FILTERED_OUT;
        reply.filterReason = FilterReason.MERGED_REPLY;
        reply.persist();
        DecisionLogEntry.record(target.id, "prefilter", "message " + reply.id,
                "{\"merged_into\":" + target.id + "}");
    }

    private static final int MAX_BODY = 50_000;
}
