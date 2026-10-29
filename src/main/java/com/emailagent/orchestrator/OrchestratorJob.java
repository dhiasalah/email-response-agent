package com.emailagent.orchestrator;

import com.emailagent.mailbox.MailboxImporter;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;

/** The heartbeat: import new mail, then move every waiting email forward. Never two runs at the same time. */
@ApplicationScoped
public class OrchestratorJob {

    private final MailboxImporter importer;
    private final Orchestrator orchestrator;

    public OrchestratorJob(MailboxImporter importer, Orchestrator orchestrator) {
        this.importer = importer;
        this.orchestrator = orchestrator;
    }

    @Scheduled(every = "{emailagent.orchestrator-every}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        MailboxImporter.ImportResult imported = importer.importNew();
        int moved = orchestrator.processPending();
        if (imported.imported() + imported.filtered() + moved > 0) {
            Log.infof("tick: %d imported, %d filtered, %d transitions", imported.imported(), imported.filtered(), moved);
        }
    }
}
