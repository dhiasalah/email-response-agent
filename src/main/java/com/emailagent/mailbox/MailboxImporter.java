package com.emailagent.mailbox;

import com.emailagent.model.EmailStatus;
import com.emailagent.model.FilterReason;
import com.emailagent.security.PreFilter;
import com.emailagent.security.Sanitizer;
import com.emailagent.store.DecisionLogEntry;
import com.emailagent.store.EmailEntity;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Moves new mail into the shared state: skip duplicates, pre-filter, sanitize, store.
 * One transaction per email, so one broken message can't block the others (availability).
 */
@ApplicationScoped
public class MailboxImporter {

    public record ImportResult(int imported, int filtered, int duplicates, int rejected) {}

    private final Mailbox mailbox;
    private final PreFilter preFilter;
    private final Sanitizer sanitizer;

    public MailboxImporter(Mailbox mailbox, PreFilter preFilter, Sanitizer sanitizer) {
        this.mailbox = mailbox;
        this.preFilter = preFilter;
        this.sanitizer = sanitizer;
    }

    public ImportResult importNew() {
        int imported = 0;
        int filtered = 0;
        int duplicates = 0;
        int rejected = 0;
        for (IncomingEmail in : mailbox.fetch()) {
            try {
                Outcome outcome = QuarkusTransaction.requiringNew().call(() -> importOne(in));
                switch (outcome) {
                    case IMPORTED -> imported++;
                    case FILTERED -> filtered++;
                    case DUPLICATE -> duplicates++;
                }
            } catch (RuntimeException e) {
                // never log the content; the exception type is enough to investigate with the message id
                Log.warnf("rejected message %s (%s)", in.messageId(), e.getClass().getSimpleName());
                rejected++;
            }
        }
        return new ImportResult(imported, filtered, duplicates, rejected);
    }

    private enum Outcome { IMPORTED, FILTERED, DUPLICATE }

    private Outcome importOne(IncomingEmail in) {
        if (EmailEntity.findByMessageId(in.messageId()).isPresent()) {
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
        email.body = body.text();
        Set<String> flags = new TreeSet<>(body.flags());
        name.flags().forEach(f -> flags.add("name_" + f));
        subject.flags().forEach(f -> flags.add("subject_" + f));
        email.sanitizerFlags = flags.isEmpty() ? null : String.join(",", flags);
        email.persist();
        DecisionLogEntry.record(email.id, "prefilter", "message " + email.id,
                "{\"filtered\":null,\"flags\":\"" + (email.sanitizerFlags == null ? "" : email.sanitizerFlags) + "\"}");
        return Outcome.IMPORTED;
    }
}
