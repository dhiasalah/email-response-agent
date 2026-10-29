package com.emailagent.model;

/** Anything other than LOW goes straight to the owner (design doc §4.1). */
public enum Sensitivity {
    LOW,
    PERSONAL,
    COMPLAINT,
    LEGAL,
    HEALTH
}
