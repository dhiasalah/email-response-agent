package com.emailagent.config;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Reads AppConfig at start-up. Quarkus only validates a @ConfigMapping that something injects,
 * so without this bean a typo like SEND_MODE=autto would not stop the application.
 */
@ApplicationScoped
public class StartupConfigCheck {

    void onStart(@Observes StartupEvent event, AppConfig config) {
        Log.infof("Send mode: %s", config.sendMode());
    }
}
