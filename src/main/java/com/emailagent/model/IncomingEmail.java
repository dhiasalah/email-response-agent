package com.emailagent.model;

import java.time.Instant;
import java.util.Map;

/**
 * One raw message as the mailbox delivers it. Everything here is untrusted, headers included:
 * only deterministic code (pre-filter, sanitizer) reads it, never an LLM.
 */
public record IncomingEmail(
        String messageId,
        String threadId,
        String from,
        String fromName,
        String subject,
        String bodyText,
        String bodyHtml,
        Instant receivedAt,
        Map<String, String> headers) {

    public IncomingEmail {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** Header lookup ignoring case, as RFC 5322 header names are case-insensitive. */
    public String header(String name) {
        return headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
