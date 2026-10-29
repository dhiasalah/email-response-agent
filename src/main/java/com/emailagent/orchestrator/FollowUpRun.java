package com.emailagent.orchestrator;

import com.emailagent.agents.TriageAgent;
import com.emailagent.config.AppConfig;
import com.emailagent.model.EmailStatus;
import com.emailagent.model.NoticeKind;
import com.emailagent.store.EmailEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;

/**
 * The timers (design doc §6, §16.2 "Timers"): emails waiting on their sender or on a card are looked at here, each
 * through the orchestrator (same idempotence key, lifecycle check and audit row as any step). Only the emails that
 * have something to do are selected, so a long wait can't starve the others.
 */
@ApplicationScoped
public class FollowUpRun {

    public record Result(int clarifications, int holdingReplies) {}

    private final Orchestrator orchestrator;
    private final SenderReplyStep senderStep;
    private final HoldingReplyStep holdingStep;
    private final AppConfig config;

    public FollowUpRun(Orchestrator orchestrator, TriageAgent triage, SendDispatcher dispatcher, AppConfig config) {
        this.orchestrator = orchestrator;
        this.config = config;
        this.senderStep = new SenderReplyStep(triage, dispatcher, config.noticeAutoSend(),
                config.clarificationTimeout(), config.ownerName(), config.aiDisclosure());
        this.holdingStep = new HoldingReplyStep(dispatcher, config.noticeAutoSend(), config.ownerName(),
                config.aiDisclosure());
    }

    public Result run() {
        Instant timedOut = Instant.now().minus(config.clarificationTimeout());
        // answered, nothing pending (ask or give up), or the pending question timed out
        List<Long> waitingOnSender = ids("""
                status = ?1 and (senderReplied = true
                  or not exists (select n.id from NoticeEntity n where n.email.id = e.id and n.kind = ?2
                                 and n.answeredAt is null)
                  or exists (select n.id from NoticeEntity n where n.email.id = e.id and n.kind = ?2
                             and n.answeredAt is null and n.createdAt < ?3))
                """, EmailStatus.AWAITING_SENDER, NoticeKind.CLARIFICATION, timedOut);
        int clarifications = 0;
        for (long id : waitingOnSender) {
            if (orchestrator.runStep(senderStep, id)) {
                clarifications++;
            }
        }
        int holding = 0;
        if (config.holdingReplyEnabled()) {
            List<Long> waitingOnCard = ids("""
                    status = ?1 and receivedAt < ?2
                      and not exists (select n.id from NoticeEntity n where n.email.id = e.id and n.kind = ?3)
                    """, EmailStatus.AWAITING_INPUT, Instant.now().minus(config.holdingReplyAfter()),
                    NoticeKind.HOLDING);
            for (long id : waitingOnCard) {
                if (orchestrator.runStep(holdingStep, id)) {
                    holding++;
                }
            }
        }
        return new Result(clarifications, holding);
    }

    private List<Long> ids(String where, Object... params) {
        return QuarkusTransaction.requiringNew().call(() ->
                EmailEntity.<EmailEntity>find("from EmailEntity e where " + where + " order by e.receivedAt, e.id",
                                params)
                        .page(0, config.orchestratorBatchSize()).list().stream().map(e -> e.id).toList());
    }

    SenderReplyStep senderStep() {
        return senderStep;
    }
}
