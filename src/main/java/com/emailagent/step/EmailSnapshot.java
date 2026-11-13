package com.emailagent.step;

import com.emailagent.model.EmailStatus;
import com.emailagent.model.Intent;
import com.emailagent.model.Language;
import com.emailagent.model.SenderScope;
import com.emailagent.model.Sensitivity;
import com.emailagent.model.SlotOwner;
import com.emailagent.model.SlotStatus;
import com.emailagent.model.SlotType;
import com.emailagent.store.EmailEntity;
import com.emailagent.store.SlotEntity;
import java.time.Instant;
import java.util.List;

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
        Boolean replyNeeded,
        String sanitizerFlags,
        List<Slot> slots) {

    public record Slot(long id, SlotType type, String entity, String qualifier, String question, SlotOwner owner,
            SlotStatus status, String value, String source) {

        static Slot of(SlotEntity s) {
            return new Slot(s.id, s.type, s.entity, s.qualifier, s.question, s.owner, s.status, s.value, s.source);
        }
    }

    /** Call inside a transaction: the slots are loaded lazily. */
    public static EmailSnapshot of(EmailEntity e) {
        return new EmailSnapshot(e.id, e.version, e.status, e.messageId, e.threadId, e.sender, e.senderName,
                e.subject, e.body, e.receivedAt, e.scope(), e.intent, e.sensitivity, e.language, e.replyNeeded,
                e.sanitizerFlags, e.slots.stream().map(Slot::of).toList());
    }
}
