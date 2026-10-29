package com.emailagent.mailbox;

import com.emailagent.model.OutboxKind;

/**
 * A reply handed to the mailbox, built by code from an outbox row: one recipient (the original sender), no copy,
 * no attachment. {@code messageId} is stable, so delivering the same row twice is recognizable.
 */
public record OutgoingMessage(OutboxKind kind, String to, String subject, String body, String inReplyTo,
        String messageId) {}
