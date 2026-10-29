package com.emailagent.mailbox;

import java.util.List;

/** Where emails come from: JSON fixtures for the whole course, Microsoft Graph in the optional lesson 17. */
public interface Mailbox {

    List<IncomingEmail> fetch();
}
