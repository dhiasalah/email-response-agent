package com.emailagent.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** All emailagent.* settings, typed and checked at start-up. */
@ConfigMapping(prefix = "emailagent")
public interface AppConfig {

    /** draft | auto. Any other value stops the application at start-up. */
    SendMode sendMode();

    /** The mailbox owner's address. Mail from it is never processed (reply loops). */
    String ownerAddress();

    /** More emails than this from one sender within an hour are filtered out (flood protection). */
    @WithDefault("10")
    int maxEmailsPerSenderPerHour();

    /** Classpath JSON file the mock mailbox reads. */
    @WithDefault("mailbox/inbox.json")
    String mailboxFixture();

    /** How often the orchestrator imports mail and moves emails forward. */
    @WithDefault("30s")
    String orchestratorEvery();

    /** Emails handled per step and per run: bounds the work (and LLM calls) of one tick. */
    @WithDefault("20")
    int orchestratorBatchSize();
}
