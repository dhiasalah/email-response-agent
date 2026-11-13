package com.emailagent.mailbox;

import com.emailagent.config.AppConfig;
import com.emailagent.model.IncomingEmail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The owner's Gmail as the system's mailbox (design doc §10: the mail integration sits outside the agents, behind
 * {@link Mailbox}). Reads only allowed senders, writes only drafts. Selected with {@code emailagent.mailbox=gmail};
 * the mock stays the default everywhere else.
 */
@ApplicationScoped
@IfBuildProperty(name = "emailagent.mailbox", stringValue = "gmail")
public class GmailMailbox implements Mailbox {

    /** Marks the drafts we create: when the owner sends one, the curator must not learn from the agent's own text. */
    static final String MARKER = "X-Email-Agent";

    private final GmailApi api;
    private final Set<String> allowed;
    private final String owner;
    private final int lookbackDays;
    private final Map<String, IncomingEmail> seen = new ConcurrentHashMap<>();

    public GmailMailbox(ObjectMapper mapper, AppConfig config) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.owner = config.ownerAddress();
        this.api = new GmailApi(mapper, http, new GoogleOAuth(mapper, http, Path.of(config.gmailCredentialsDir()),
                owner));
        this.allowed = config.gmailAllowedSenders().orElse(List.of()).stream().map(s -> s.strip()
                .toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        this.lookbackDays = config.gmailLookbackDays();
        if (allowed.isEmpty()) {
            Log.warn("Gmail mailbox: no allowed sender configured, nothing will be read");
        }
    }

    @Override
    public List<IncomingEmail> fetch() {
        if (allowed.isEmpty()) {
            return List.of();
        }
        List<IncomingEmail> out = new ArrayList<>();
        for (String id : api.search("in:inbox newer_than:" + lookbackDays + "d from:(" + String.join(" OR ", allowed)
                + ")", 50)) {
            IncomingEmail email = seen.computeIfAbsent(id, k -> toIncoming(api.message(k)));
            // Gmail's search is approximate: the exact From address decides
            if (allowed.contains(address(email.from()))) {
                out.add(email);
            }
        }
        return out;
    }

    /** Gmail writes its verdict (mx.google.com) on top of every message it receives. */
    @Override
    public boolean authenticatesSenders() {
        return true;
    }

    @Override
    public List<SentMessage> fetchSent() {
        if (allowed.isEmpty()) {
            return List.of();
        }
        List<SentMessage> out = new ArrayList<>();
        for (String id : api.search("in:sent newer_than:" + lookbackDays + "d to:(" + String.join(" OR ", allowed)
                + ")", 50)) {
            JsonNode msg = api.message(id);
            Map<String, String> h = headers(msg);
            if (h.containsKey(MARKER.toLowerCase(Locale.ROOT))) {
                continue; // a draft we wrote, sent by the owner: not the owner's own words
            }
            out.add(new SentMessage(h.get("message-id"), h.get("in-reply-to"), h.get("to"), text(msg.path("payload"),
                    "text/plain"), Instant.ofEpochMilli(msg.path("internalDate").asLong())));
        }
        return out;
    }

    /**
     * Always a draft, whatever the kind: in Gmail mode the owner sends from Gmail. Idempotent: a second delivery of the
     * same message (at-least-once relay) finds the first draft by its Message-ID.
     */
    @Override
    public void deliver(OutgoingMessage message) {
        if (!api.draftsWithMessageId(message.messageId()).isEmpty()) {
            return;
        }
        String threadId = null;
        if (message.inReplyTo() != null) {
            List<String> original = api.search("rfc822msgid:" + message.inReplyTo(), 1);
            if (!original.isEmpty()) {
                threadId = api.message(original.get(0)).path("threadId").asText(null);
            }
        }
        api.createDraft(draftRaw(message, owner), threadId);
    }

    // --- conversions, pure functions (tested without network) ---

    static IncomingEmail toIncoming(JsonNode msg) {
        Map<String, String> h = headers(msg);
        JsonNode payload = msg.path("payload");
        String messageId = h.getOrDefault("message-id", "<gmail-" + msg.path("id").asText() + "@gmail.invalid>");
        return new IncomingEmail(messageId, msg.path("threadId").asText(null), address(h.get("from")),
                displayName(h.get("from")), h.get("subject"), text(payload, "text/plain"), text(payload, "text/html"),
                Instant.ofEpochMilli(msg.path("internalDate").asLong()), h);
    }

    /** Header names in lower case (RFC 5322 names are case-insensitive), first value kept. */
    static Map<String, String> headers(JsonNode msg) {
        Map<String, String> out = new LinkedHashMap<>();
        msg.path("payload").path("headers").forEach(h -> out.putIfAbsent(h.path("name").asText()
                .toLowerCase(Locale.ROOT), h.path("value").asText()));
        return out;
    }

    /** The first part of this type, searched through nested multiparts, decoded; null if none. */
    static String text(JsonNode part, String mimeType) {
        if (mimeType.equalsIgnoreCase(part.path("mimeType").asText()) && part.path("body").has("data")) {
            return new String(Base64.getUrlDecoder().decode(part.path("body").path("data").asText()),
                    StandardCharsets.UTF_8);
        }
        for (JsonNode child : part.path("parts")) {
            String found = text(child, mimeType);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** "Lina B <lina@x.org>" → "lina@x.org"; a bare address → itself, lower case. */
    static String address(String from) {
        if (from == null) {
            return null;
        }
        int lt = from.lastIndexOf('<');
        int gt = from.lastIndexOf('>');
        String addr = lt >= 0 && gt > lt ? from.substring(lt + 1, gt) : from;
        return addr.strip().toLowerCase(Locale.ROOT);
    }

    static String displayName(String from) {
        if (from == null || from.indexOf('<') <= 0) {
            return null;
        }
        return from.substring(0, from.indexOf('<')).replace("\"", "").strip();
    }

    /** The draft as an RFC 822 message, base64url as Gmail wants it. Body in base64, subject RFC 2047 if needed. */
    static String draftRaw(OutgoingMessage m, String from) {
        StringBuilder raw = new StringBuilder();
        raw.append("From: ").append(oneLine(from)).append("\r\n");
        raw.append("To: ").append(oneLine(m.to())).append("\r\n");
        raw.append("Subject: ").append(encodeHeader(oneLine(m.subject()))).append("\r\n");
        raw.append("Message-ID: ").append(oneLine(m.messageId())).append("\r\n");
        if (m.inReplyTo() != null) {
            raw.append("In-Reply-To: ").append(oneLine(m.inReplyTo())).append("\r\n");
            raw.append("References: ").append(oneLine(m.inReplyTo())).append("\r\n");
        }
        raw.append(MARKER).append(": draft\r\n");
        raw.append("MIME-Version: 1.0\r\n");
        raw.append("Content-Type: text/plain; charset=UTF-8\r\n");
        raw.append("Content-Transfer-Encoding: base64\r\n\r\n");
        raw.append(Base64.getMimeEncoder().encodeToString(m.body().getBytes(StandardCharsets.UTF_8)));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** No line break can reach a header, whatever the value (header injection). */
    static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").strip();
    }

    static String encodeHeader(String value) {
        if (value.chars().allMatch(c -> c < 128)) {
            return value;
        }
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "?=";
    }
}
