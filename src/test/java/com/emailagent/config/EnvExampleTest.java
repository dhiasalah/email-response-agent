package com.emailagent.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * .env.example is what a new installer copies: a variable the application never reads is a setting that silently
 * does nothing (it once listed CHAT_MODEL while the models were read from FAST_MODEL and STRONG_MODEL).
 */
class EnvExampleTest {

    @Test
    void everyVariableInEnvExampleIsReadByTheApplication() throws Exception {
        String props = new String(getClass().getResourceAsStream("/application.properties").readAllBytes(), UTF_8);
        List<String> keys = Files.readAllLines(Path.of(".env.example")).stream()
                .map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .map(l -> l.substring(0, l.indexOf('='))).toList();

        assertThat(keys).isNotEmpty().allSatisfy(k -> assertThat(
                k.startsWith("EMAILAGENT_") || props.contains("${" + k + ":") || props.contains("${" + k + "}"))
                .as(k + " is not read by application.properties").isTrue());
    }
}
