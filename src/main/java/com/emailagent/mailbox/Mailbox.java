package com.emailagent.mailbox;

import java.util.List;

/** Where emails come from: JSON fixtures for now; a Microsoft Graph implementation can replace it. */
public interface Mailbox {

    List<IncomingEmail> fetch();

    /** Replies the owner sent by hand, so the curator can learn recurring answers (design doc §8.4). */
    default List<SentMessage> fetchSent() {
        return List.of();
    }
}
