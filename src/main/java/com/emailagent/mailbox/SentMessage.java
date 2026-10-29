package com.emailagent.mailbox;

import java.time.Instant;

/**
 * A reply the owner sent from the mailbox itself, outside this system (design doc §8.4). The owner's own words sit
 * above the quoted original; the quoted part is the student's text and stays untrusted.
 */
public record SentMessage(String messageId, String inReplyTo, String to, String bodyText, Instant sentAt) {}
