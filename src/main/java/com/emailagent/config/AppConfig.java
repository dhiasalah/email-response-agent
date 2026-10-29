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

    /** Classpath JSON file of replies the owner sent by hand (the mock Sent folder). */
    @WithDefault("mailbox/sent.json")
    String mailboxSentFixture();

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

    /** A fact that isn't a date gets a review date this far ahead (design doc §8.2: "procedures get a review date"). */
    @WithDefault("120")
    int factReviewDays();

    /** How far back the recent answer log is searched (design doc §8.4). */
    @WithDefault("7")
    int recentAnswerWindowDays();

    /**
     * Slot types whose repeated recent answers may fill a slot without validation. Empty at launch:
     * every recent-answer fill is only proposed until the type has proven reliable (design doc §8.4, §12).
     */
    Optional<Set<SlotType>> recentAnswerTrustedTypes();

    /**
     * Cosine similarity above which a question is a candidate for a card with a different key (design doc §7.2).
     * Only a candidate: the slot type and scope must match and the verifier model must agree.
     */
    @WithDefault("0.80")
    double groupingSimilarityThreshold();

    /** Unassigned questions handled per grouping run, newest first (design doc §7.2, bootstrap cap). */
    @WithDefault("200")
    int groupingBatchSize();

    /** An empty card closes after this long without activity (design doc §6.1). */
    @WithDefault("7")
    int clusterExpiryDays();

    /** Card priority weights (design doc §7.4): urgency first, then volume. */
    @WithDefault("0.4")
    double priorityWeightUrgency();

    @WithDefault("0.3")
    double priorityWeightVolume();

    @WithDefault("0.2")
    double priorityWeightAge();

    @WithDefault("0.1")
    double priorityWeightSender();

    /** How often the orchestrator imports mail and moves emails forward. */
    @WithDefault("30s")
    String orchestratorEvery();

    /** Emails handled per step and per run: bounds the work (and LLM calls) of one tick. */
    @WithDefault("20")
    int orchestratorBatchSize();
}
