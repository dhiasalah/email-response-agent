package com.emailagent.orchestrator;

import com.emailagent.model.EmailStatus;
import com.emailagent.model.Language;
import com.emailagent.model.NoticeKind;
import com.emailagent.model.OutboxKind;
import com.emailagent.step.AgentStep;
import com.emailagent.step.EmailSnapshot;
import com.emailagent.step.StepResult;
import com.emailagent.store.NoticeEntity;
import com.emailagent.store.OutboxEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import java.time.Instant;

/**
 * One holding reply to an email whose question has waited on a card too long (design doc §6 timeouts). The email
 * stays AWAITING_INPUT; the reply says only that it was received. Run by {@link FollowUpRun} on emails past the delay.
 */
public class HoldingReplyStep implements AgentStep {

    private final SendDispatcher dispatcher;
    private final boolean noticeAutoSend;
    private final String ownerName;
    private final boolean disclosure;

    public HoldingReplyStep(SendDispatcher dispatcher, boolean noticeAutoSend, String ownerName, boolean disclosure) {
        this.dispatcher = dispatcher;
        this.noticeAutoSend = noticeAutoSend;
        this.ownerName = ownerName;
        this.disclosure = disclosure;
    }

    @Override
    public String name() {
        return "holding";
    }

    @Override
    public EmailStatus trigger() {
        return EmailStatus.AWAITING_INPUT;
    }

    @Override
    public StepResult run(EmailSnapshot email) {
        long[] sent = QuarkusTransaction.requiringNew().call(() -> {
            boolean already = NoticeEntity.count("email.id = ?1 and kind = ?2", email.id(), NoticeKind.HOLDING) > 0;
            return already ? null : SendDispatcher.sentInLastDay(email.sender());
        });
        if (sent == null) {
            return StepResult.waiting(EmailStatus.AWAITING_INPUT); // once per email (a unique index agrees)
        }
        Language lang = email.language() == null ? Language.EN : email.language();
        OutboxKind kind = SendDispatcher.noticeKind(dispatcher.policy(), noticeAutoSend, sent[0], sent[1]);
        return StepResult.to(EmailStatus.AWAITING_INPUT, "{\"holding\":\"" + kind.name().toLowerCase() + "\"}")
                .withWrites(e -> {
                    NoticeEntity n = new NoticeEntity();
                    n.kind = NoticeKind.HOLDING;
                    n.email = e;
                    n.recipient = e.sender;
                    n.subject = SendDispatcher.replySubject(e.subject);
                    n.body = NoticeTemplates.holding(lang, ownerName, disclosure);
                    n.createdAt = Instant.now();
                    n.persist();
                    OutboxEntity.forNotice(n, kind, dispatcher.domain());
                });
    }
}
