package com.emailagent.mailbox;

import java.util.List;

/** Where emails come from: JSON fixtures for now; a Microsoft Graph implementation can replace it. */
public interface Mailbox {

    List<IncomingEmail> fetch();
}
