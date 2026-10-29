package com.emailagent.web;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/** An owner account for the panel tests: the hash is computed at start-up, so no password or hash is committed. */
public class OwnerProfile implements QuarkusTestProfile {

    static final String USERNAME = "owner";
    /** A test fixture, not a secret: it only opens a panel started by these tests. */
    static final String PASSWORD = "panel-test-only";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("emailagent.owner-password-hash", PasswordHash.hash(PASSWORD));
    }
}
