package com.emailagent.config;

import com.emailagent.model.SlotType;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.List;
import java.util.Set;

/**
 * Reads AppConfig at start-up. Quarkus only validates a @ConfigMapping that something injects,
 * so without this bean a typo like SEND_MODE=autto would not stop the application.
 */
@ApplicationScoped
public class StartupConfigCheck {

    void onStart(@Observes StartupEvent event, AppConfig config) {
        Set<SlotType> autoTypes = config.autoSendTypes().orElse(Set.of());
        checkAutoSendTypes(autoTypes);
        Log.infof("Send mode: %s, auto-send types: %s", config.sendMode(), autoTypes);
    }

    /** Configuration can narrow the policy, never widen it: a personal answer is never sent without the owner. */
    static void checkAutoSendTypes(Set<SlotType> types) {
        List<SlotType> personal = types.stream().filter(t -> !t.shareable()).sorted().toList();
        if (!personal.isEmpty()) {
            throw new IllegalStateException("emailagent.auto-send-types lists personal slot types " + personal
                    + "; only shareable types can be sent automatically");
        }
    }
}
