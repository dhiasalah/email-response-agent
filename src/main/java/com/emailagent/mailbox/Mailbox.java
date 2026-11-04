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

    /**
     * Removes the draft saved under this Message-ID from the owner's drafts, after the owner rejected it, so the
     * rejected text can't be sent from the mailbox. Idempotent: nothing happens when no such draft is there (already
     * removed, or sent or deleted by hand). Called only by the outbox relay. Never touches a message that was sent.
     */
    default void discardDraft(String messageId) {
        throw new UnsupportedOperationException("this mailbox cannot discard drafts");
    }
}
