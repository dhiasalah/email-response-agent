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
}
