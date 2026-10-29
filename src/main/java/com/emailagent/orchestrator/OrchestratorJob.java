package com.emailagent.orchestrator;

import com.emailagent.mailbox.MailboxImporter;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The heartbeat: import new mail, learn from the owner's sent replies, move every waiting email forward, group open
 * questions. Never two runs at once.
 */
@ApplicationScoped
public class OrchestratorJob {

    private final MailboxImporter importer;
    private final Orchestrator orchestrator;
    private final GroupingRun grouping;
    private final ReplyLearning replyLearning;
    private final FollowUpRun followUps;
    private final BroadcastRun broadcasts;
    private final OutboxRelay relay;

    public OrchestratorJob(MailboxImporter importer, Orchestrator orchestrator, GroupingRun grouping,
            ReplyLearning replyLearning, FollowUpRun followUps, BroadcastRun broadcasts, OutboxRelay relay) {
        this.importer = importer;
        this.orchestrator = orchestrator;
        this.grouping = grouping;
        this.replyLearning = replyLearning;
        this.followUps = followUps;
        this.broadcasts = broadcasts;
        this.relay = relay;
    }

    @Scheduled(every = "{emailagent.orchestrator-every}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        MailboxImporter.ImportResult imported = importer.importNew(); // sender answers merge into their email here
        // what the owner answered by hand, before the resolver looks for it
        ReplyLearning.Result learned = replyLearning.learnFromSent();
        int moved = orchestrator.processPending();
        // emails now AWAITING_INPUT get their questions onto cards
        GroupingRun.Result grouped = grouping.run();
        // timers: clarifications, sender answers, timeouts, holding replies; then broadcast suggestions
        FollowUpRun.Result followed = followUps.run();
        int suggested = broadcasts.suggest();
        // replies committed to the outbox this tick (or left by a crash) reach the mailbox
        int delivered = relay.deliverPending();
        if (imported.imported() + imported.filtered() + learned.pairs() + moved + grouped.assigned()
                + grouped.closed() + followed.clarifications() + followed.holdingReplies() + suggested + delivered > 0) {
            Log.infof("tick: %d imported, %d filtered, %d answers learned, %d transitions, %d questions grouped"
                            + " (%d new cards), %d cards closed, %d sender follow-ups, %d holding replies,"
                            + " %d broadcasts suggested, %d messages delivered", imported.imported(),
                    imported.filtered(), learned.pairs(), moved, grouped.assigned(), grouped.newCards(),
                    grouped.closed(), followed.clarifications(), followed.holdingReplies(), suggested, delivered);
        }
    }
}
