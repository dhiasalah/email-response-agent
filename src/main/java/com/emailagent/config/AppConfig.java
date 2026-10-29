package com.emailagent.config;

import io.smallrye.config.ConfigMapping;

/** All emailagent.* settings, typed and checked at start-up. */
@ConfigMapping(prefix = "emailagent")
public interface AppConfig {

    /** draft | auto. Any other value stops the application at start-up. */
    SendMode sendMode();
}
