package com.emailagent.config;

import com.emailagent.model.SlotType;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Reads AppConfig at start-up. Quarkus only validates a @ConfigMapping that something injects,
 * so without this bean a typo like SEND_MODE=autto would not stop the application.
 */
@ApplicationScoped
public class StartupConfigCheck {

    void onStart(@Observes StartupEvent event, AppConfig config,
            @ConfigProperty(name = "quarkus.langchain4j.ollama.base-url") String llmUrl,
            @ConfigProperty(name = "quarkus.langchain4j.ollama.strong.base-url") String strongLlmUrl) {
        Set<SlotType> autoTypes = config.autoSendTypes().orElse(Set.of());
        checkAutoSendTypes(autoTypes);
        if (!config.allowRemoteLlm()) {
            checkLocal(llmUrl);
            checkLocal(strongLlmUrl);
        }
        Log.infof("Send mode: %s, auto-send types: %s", config.sendMode(), autoTypes);
    }

    /**
     * Student emails go only to a model on this machine (design doc §13: no data to a provider without a processing
     * agreement). A typo or an OLLAMA_HOST pointing elsewhere stops the start instead of sending mail out.
     */
    static void checkLocal(String url) {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            host = null;
        }
        if (host == null || !(host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]")
                || host.equals("::1"))) {
            throw new IllegalStateException("the model address " + url + " is not on this machine; set "
                    + "emailagent.allow-remote-llm=true only with a data processing agreement");
        }
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
