package com.emailagent.config;

import com.emailagent.model.SlotType;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** All emailagent.* settings, typed and checked at start-up. */
@ConfigMapping(prefix = "emailagent")
public interface AppConfig {

    /** draft | auto. Any other value stops the application at start-up. */
    SendMode sendMode();

    /** The mailbox owner's address. Mail from it is never processed (reply loops). */
    String ownerAddress();

    /** How replies are signed. The writer model never signs: code adds this. */
    @WithDefault("Prof. Martin")
    String ownerName();

    /**
     * Adds a line saying the reply was prepared with an AI assistant (design doc §13, §15: decided on by default;
     * the institution's DPO may require it, and students deserve to know).
     */
    @WithDefault("true")
    boolean aiDisclosure();

    /** More emails than this from one sender within an hour are filtered out (flood protection). */
    @WithDefault("10")
    int maxEmailsPerSenderPerHour();

    /**
     * Which mailbox the system reads and writes: {@code mock} (JSON fixtures, the default, tests and CI) or {@code gmail}
     * (the owner's Gmail account). Chosen when the application is built or started in dev mode.
     */
    @WithDefault("mock")
    String mailbox();

    /**
     * Gmail mode: only messages from these exact addresses are read; everything else in the mailbox is never fetched
     * or stored (personal mail of people who didn't agree). Empty: nothing is read (fail closed).
     */
    Optional<List<String>> gmailAllowedSenders();

    /** Gmail mode: the folder holding gmail-client.json and the tokens, outside the project. */
    @WithDefault("${user.home}/.emailagent")
    String gmailCredentialsDir();

    /** Gmail mode: how far back the inbox and Sent folder are searched. */
    @WithDefault("14")
    int gmailLookbackDays();

    /**
     * Reject the From header's identity unless the receiving server authenticated it (DMARC, or aligned DKIM): an
     * unauthenticated sender gets no scope, no role and no automatic reply (design doc §9, §15). Empty: on for gmail,
     * off for the mock fixtures, which carry no authentication headers. Gmail mode can't turn it off.
     */
    Optional<Boolean> requireSenderAuth();

    /** The receiving server whose Authentication-Results header is trusted; a header from anyone else is ignored. */
    @WithDefault("mx.google.com")
    String trustedAuthservId();

    /** Classpath JSON file the mock mailbox reads. */
    @WithDefault("mailbox/inbox.json")
    String mailboxFixture();

    /** Classpath JSON file of replies the owner sent by hand (the mock Sent folder). */
    @WithDefault("mailbox/sent.json")
    String mailboxSentFixture();

    /** Classpath JSON roster that gives each known sender a scope (program, track, year). */
    @WithDefault("directory/senders.json")
    String senderDirectoryFixture();

    /**
     * Folder of specialized agent definitions, one {@code <id>.json} per agent, with the prompt and context files
     * each one names (brief §5). Read from disk, not the classpath: the owner edits agents without touching code.
     */
    @WithDefault("agents")
    String agentsDir();

    /** Below this routing score no specialized agent is suitable and the fallback agent takes the email. */
    @WithDefault("0.3")
    double routingMinScore();

    /** Two agents closer than this are both suitable: the one listed first (its {@code order}) wins, flagged. */
    @WithDefault("0.1")
    double routingTieMargin();

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

    /**
     * Slot types whose reviewed replies may be sent without the owner (design doc §9, send modes), when send-mode is
     * auto. Empty at launch: every reply is a draft until a type has proven reliable. Only shareable types are
     * accepted; a personal type here stops the application at start-up.
     */
    Optional<Set<SlotType>> autoSendTypes();

    /** Automatic replies to one sender within 24 hours; beyond it, replies become drafts (design doc §9). */
    @WithDefault("3")
    int maxAutoRepliesPerSenderPerDay();

    /** Automatic replies in total within 24 hours: bounds a loop or mass-mailing incident. */
    @WithDefault("50")
    int maxAutoRepliesPerDay();

    /** Outgoing replies handed to the mailbox per run. */
    @WithDefault("20")
    int outboxBatchSize();

    /** Classpath JSON of cohort mailing lists (program, track, year → list address): where a broadcast can go. */
    @WithDefault("directory/cohorts.json")
    String cohortDirectoryFixture();

    /**
     * Clarifications and holding replies may be sent without the owner when send-mode is auto (design doc §7.5:
     * low risk, so they can move to auto-send before answers). Off at launch: they are saved as drafts.
     */
    @WithDefault("false")
    boolean noticeAutoSend();

    /** No answer to a clarification after this long: the email is closed (design doc §7.5). */
    @WithDefault("72h")
    Duration clarificationTimeout();

    /**
     * A question still waiting on a card after this long gets one holding reply (design doc §6 timeouts, §15 open
     * question: decided at 48 hours by default).
     */
    @WithDefault("48h")
    Duration holdingReplyAfter();

    @WithDefault("true")
    boolean holdingReplyEnabled();

    /** Questions on one card, within the window, that make a broadcast worth suggesting (design doc §7.7). */
    @WithDefault("5")
    int broadcastMinQuestions();

    @WithDefault("7")
    int broadcastWindowDays();

    /** The review panel's login name (one owner). */
    @WithDefault("owner")
    String ownerUsername();

    /**
     * The owner's password as a PBKDF2 hash (see PasswordHash), from the OWNER_PASSWORD_HASH environment variable.
     * Not set: the panel refuses every login.
     */
    Optional<String> ownerPasswordHash();

    /** Finished emails are erased this long after their last change (design doc §13: one academic year). */
    @WithDefault("365")
    int retentionDays();

    /** When the retention job runs (Quartz-style cron): every night at 03:00. */
    @WithDefault("0 0 3 * * ?")
    String retentionCron();

    /**
     * The models run on this machine, so student emails never leave it (design doc §13, model provider). A
     * non-local Ollama address stops the application at start-up unless this is set on purpose.
     */
    @WithDefault("false")
    boolean allowRemoteLlm();

    /** How often the orchestrator imports mail and moves emails forward. */
    @WithDefault("30s")
    String orchestratorEvery();

    /** Emails handled per step and per run: bounds the work (and LLM calls) of one tick. */
    @WithDefault("20")
    int orchestratorBatchSize();
}
