package com.emailagent.orchestrator;

import com.emailagent.model.EmailStatus;
import com.emailagent.model.Intent;
import com.emailagent.model.Language;
import com.emailagent.model.SenderScope;
import com.emailagent.model.Sensitivity;
import com.emailagent.store.EmailEntity;
import java.time.Instant;

/**
 * A read-only copy of an email handed to an agent. Agents can't save it or change its status;
 * {@code status} + {@code version} are the idempotence key the orchestrator checks before committing a result.
 */
public record EmailSnapshot(
        long id,
        long version,
        EmailStatus status,
        String messageId,
        String threadId,
        String sender,
        String senderName,
        String subject,
        String body,
        Instant receivedAt,
        SenderScope scope,
        Intent intent,
        Sensitivity sensitivity,
        Language language,
        String sanitizerFlags) {

    public static EmailSnapshot of(EmailEntity e) {
        return new EmailSnapshot(e.id, e.version, e.status, e.messageId, e.threadId, e.sender, e.senderName,
                e.subject, e.body, e.receivedAt, e.scope(), e.intent, e.sensitivity, e.language, e.sanitizerFlags);
    }
}
