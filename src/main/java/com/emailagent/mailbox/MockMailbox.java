package com.emailagent.mailbox;

import com.emailagent.config.AppConfig;
import com.emailagent.model.IncomingEmail;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.properties.UnlessBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A mailbox backed by a JSON file on the classpath: repeatable demos and tests, no real student data. */
@ApplicationScoped
@UnlessBuildProperty(name = "emailagent.mailbox", stringValue = "gmail", enableIfMissing = true)
public class MockMailbox implements Mailbox {

    private final ObjectMapper mapper;
    private final String fixture;
    private final String sentFixture;

    public MockMailbox(ObjectMapper mapper, AppConfig config) {
        this.mapper = mapper;
        this.fixture = config.mailboxFixture();
        this.sentFixture = config.mailboxSentFixture();
    }

    @Override
    public List<IncomingEmail> fetch() {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(fixture)) {
            if (in == null) {
                throw new IllegalStateException("mailbox fixture not found: " + fixture);
            }
            return mapper.readValue(in, new TypeReference<List<IncomingEmail>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read mailbox fixture " + fixture, e);
        }
    }

    /** Delivered replies by message id: a second delivery of the same message is ignored, like a real server would. */
    private final Map<String, OutgoingMessage> delivered = new ConcurrentHashMap<>();

    @Override
    public void deliver(OutgoingMessage message) {
        delivered.putIfAbsent(message.messageId(), message);
    }

    /** What reached the mailbox (tests, demo). Kept apart from the Sent fixture: the curator never learns from it. */
    public List<OutgoingMessage> delivered() {
        return List.copyOf(delivered.values());
    }

    @Override
    public List<SentMessage> fetchSent() {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(sentFixture)) {
            if (in == null) {
                return List.of(); // no Sent folder in this demo
            }
            return mapper.readValue(in, new TypeReference<List<SentMessage>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read sent fixture " + sentFixture, e);
        }
    }
}
