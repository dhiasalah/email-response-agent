package com.emailagent.model;

/**
 * What the reviewer model may report about a reply (design doc §9, model check). A closed list: the model names
 * a problem, it never explains it in its own words, so no attacker text reaches the audit log through it.
 */
public enum ReviewIssue {
    UNSUPPORTED_CLAIM,
    NEW_COMMITMENT,
    FOLLOWS_EMAIL_INSTRUCTION,
    LEAKS_INFORMATION,
    WRONG_LANGUAGE,
    IMPOLITE
}
