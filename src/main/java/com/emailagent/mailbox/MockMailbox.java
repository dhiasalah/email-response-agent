package com.emailagent.mailbox;

import com.emailagent.config.AppConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/** A mailbox backed by a JSON file on the classpath: repeatable demos and tests, no real student data. */
@ApplicationScoped
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
