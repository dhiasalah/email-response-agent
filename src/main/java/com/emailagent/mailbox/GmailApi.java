package com.emailagent.mailbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The only Gmail operations this system uses: search, read one message, find a draft, create a draft. There is no
 * method that sends mail, and a test fails the build if a send endpoint appears in this package: the Gmail scope
 * would allow sending, so "drafts only" is guaranteed here, in code (design doc §9, §11).
 */
final class GmailApi {

    private static final String BASE = "https://gmail.googleapis.com/gmail/v1/users/me/";

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final GoogleOAuth oauth;

    GmailApi(ObjectMapper mapper, HttpClient http, GoogleOAuth oauth) {
        this.mapper = mapper;
        this.http = http;
        this.oauth = oauth;
    }

    /** Message ids matching a Gmail search (newest first), at most {@code max}. */
    List<String> search(String query, int max) {
        JsonNode list = get("messages?maxResults=" + max + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
        List<String> ids = new ArrayList<>();
        list.path("messages").forEach(m -> ids.add(m.path("id").asText()));
        return ids;
    }

    /** One message with its headers and decoded-ready body parts. */
    JsonNode message(String id) {
        return get("messages/" + id + "?format=full");
    }

    /** Ids of drafts whose RFC Message-ID is this one: lets a second delivery recognize the first. */
    List<String> draftsWithMessageId(String rfcMessageId) {
        return search("in:drafts rfc822msgid:" + rfcMessageId, 5);
    }

    /** Saves a draft (RFC 822, base64url) in the owner's Drafts, in a thread when one is given. Never sends it. */
    void createDraft(String rawBase64Url, String threadId) {
        var message = mapper.createObjectNode().put("raw", rawBase64Url);
        if (threadId != null) {
            message.put("threadId", threadId);
        }
        var body = mapper.createObjectNode().set("message", message);
        call(HttpRequest.newBuilder(URI.create(BASE + "drafts"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
    }

    private JsonNode get(String path) {
        return call(HttpRequest.newBuilder(URI.create(BASE + path)).GET());
    }

    private JsonNode call(HttpRequest.Builder request) {
        HttpRequest r = request.timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + oauth.accessToken()).build();
        try {
            HttpResponse<String> response = http.send(r, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                // Google's error status only: the body could quote message content
                String status = mapper.readTree(response.body()).path("error").path("status")
                        .asText("http_" + response.statusCode());
                throw new IllegalStateException("Gmail API refused the request: " + status);
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException("Gmail API unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
