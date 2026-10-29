package com.emailagent.mailbox;

import java.util.List;

/** Where emails come from: JSON fixtures for now; a Microsoft Graph implementation can replace it. */
public interface Mailbox {

    List<IncomingEmail> fetch();

    /** Replies the owner sent by hand, so the curator can learn recurring answers (design doc §8.4). */
    default List<SentMessage> fetchSent() {
        return List.of();
    }

    /**
     * Saves the reply in the owner's drafts or sends it, by its kind. Called only by the outbox relay, with a row
     * the database already checked (design doc §11: send and create_draft belong to the orchestrator).
     */
    default void deliver(OutgoingMessage message) {
        throw new UnsupportedOperationException("this mailbox cannot deliver replies");
    }
}
