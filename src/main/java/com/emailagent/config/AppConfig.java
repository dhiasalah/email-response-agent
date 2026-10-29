package com.emailagent.config;

import com.emailagent.model.SlotType;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;
import java.util.Set;

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

    /** Classpath JSON roster that gives each known sender a scope (program, track, year). */
    @WithDefault("directory/senders.json")
    String senderDirectoryFixture();

    /** Classpath JSON knowledge base (syllabus, regulations): a match fills a slot. */
    @WithDefault("knowledge/kb.json")
    String knowledgeBaseFixture();

    /** Classpath JSON calendar: a tagged event proposes a value for the owner to confirm. */
    @WithDefault("knowledge/calendar.json")
    String calendarFixture();

    /** A fact older than this is proposed for review instead of used directly (design doc §8.1). */
    @WithDefault("90")
    int factFreshnessDays();

    /** How far back the recent answer log is searched (design doc §8.4). */
    @WithDefault("7")
    int recentAnswerWindowDays();

    /**
     * Slot types whose repeated recent answers may fill a slot without validation. Empty at launch:
     * every recent-answer fill is only proposed until the type has proven reliable (design doc §8.4, §12).
     */
    Optional<Set<SlotType>> recentAnswerTrustedTypes();

    /** How often the orchestrator imports mail and moves emails forward. */
    @WithDefault("30s")
    String orchestratorEvery();

    /** Emails handled per step and per run: bounds the work (and LLM calls) of one tick. */
    @WithDefault("20")
    int orchestratorBatchSize();
}
