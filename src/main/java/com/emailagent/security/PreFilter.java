package com.emailagent.security;

import com.emailagent.config.AppConfig;
import com.emailagent.mailbox.IncomingEmail;
import com.emailagent.model.FilterReason;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Deterministic rules applied before any LLM: drop what must never get a reply.
 * Cheap, predictable, and immune to prompt injection because no model is involved.
 */
@ApplicationScoped
public class PreFilter {

    /** Raw size (HTML or text) above which an email is not processed at all. */
    public static final int MAX_RAW_CHARS = 200_000;

    private static final Pattern NO_REPLY = Pattern.compile(
            "^(no-?reply|do-?not-?reply|mailer-daemon|postmaster|bounces?)([+.\\-].*)?@.*");
    private static final Pattern AUTO_SUBJECT = Pattern.compile(
            "^(auto(matic)?[ -]?reply|out of (the )?office|absence|r[ée]ponse automatique|absent)\\b.*");

    private final String ownerAddress;
    private final int maxPerSenderPerHour;

    @Inject
    public PreFilter(AppConfig config) {
        this(config.ownerAddress(), config.maxEmailsPerSenderPerHour());
    }

    PreFilter(String ownerAddress, int maxPerSenderPerHour) {
        this.ownerAddress = ownerAddress.toLowerCase(Locale.ROOT);
        this.maxPerSenderPerHour = maxPerSenderPerHour;
    }

    /**
     * @param recentFromSender emails already received from this sender in the hour before this one
     * @return the reason to drop it, or empty if it may go on to triage
     */
    public Optional<FilterReason> check(IncomingEmail email, long recentFromSender) {
        String from = email.from() == null ? "" : email.from().toLowerCase(Locale.ROOT).strip();
        if (from.equals(ownerAddress)) {
            return Optional.of(FilterReason.OWN_ADDRESS);
        }
        if (isAutoReply(email)) {
            return Optional.of(FilterReason.AUTO_REPLY);
        }
        if (email.header("List-Id") != null || email.header("List-Unsubscribe") != null) {
            return Optional.of(FilterReason.MAILING_LIST);
        }
        if (NO_REPLY.matcher(from).matches()) {
            return Optional.of(FilterReason.NO_REPLY_SENDER);
        }
        if (length(email.bodyHtml()) > MAX_RAW_CHARS || length(email.bodyText()) > MAX_RAW_CHARS) {
            return Optional.of(FilterReason.TOO_LARGE);
        }
        if (recentFromSender >= maxPerSenderPerHour) {
            return Optional.of(FilterReason.RATE_LIMITED);
        }
        return Optional.empty();
    }

    private static boolean isAutoReply(IncomingEmail email) {
        String autoSubmitted = email.header("Auto-Submitted");
        if (autoSubmitted != null && !autoSubmitted.strip().equalsIgnoreCase("no")) {
            return true; // RFC 3834
        }
        String precedence = email.header("Precedence");
        if (precedence != null && precedence.strip().toLowerCase(Locale.ROOT).matches("bulk|list|junk|auto_reply")) {
            return true;
        }
        if (email.header("X-Autoreply") != null || email.header("X-Autorespond") != null) {
            return true;
        }
        String subject = email.subject() == null ? "" : email.subject().strip().toLowerCase(Locale.ROOT);
        return AUTO_SUBJECT.matcher(subject).matches();
    }

    private static int length(String s) {
        return s == null ? 0 : s.length();
    }
}
