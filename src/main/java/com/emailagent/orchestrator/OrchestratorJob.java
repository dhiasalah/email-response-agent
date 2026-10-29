package com.emailagent.orchestrator;

import com.emailagent.mailbox.MailboxImporter;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;

/** The heartbeat: import new mail, move every waiting email forward, group open questions. Never two runs at once. */
@ApplicationScoped
public class OrchestratorJob {

    private final MailboxImporter importer;
    private final Orchestrator orchestrator;
    private final GroupingRun grouping;

    public OrchestratorJob(MailboxImporter importer, Orchestrator orchestrator, GroupingRun grouping) {
        this.importer = importer;
        this.orchestrator = orchestrator;
        this.grouping = grouping;
    }

    @Scheduled(every = "{emailagent.orchestrator-every}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        MailboxImporter.ImportResult imported = importer.importNew();
        int moved = orchestrator.processPending();
        // emails now AWAITING_INPUT get their questions onto cards
        GroupingRun.Result grouped = grouping.run();
        if (imported.imported() + imported.filtered() + moved + grouped.assigned() + grouped.closed() > 0) {
            Log.infof("tick: %d imported, %d filtered, %d transitions, %d questions grouped (%d new cards), %d cards closed",
                    imported.imported(), imported.filtered(), moved, grouped.assigned(), grouped.newCards(),
                    grouped.closed());
        }
    }
}
