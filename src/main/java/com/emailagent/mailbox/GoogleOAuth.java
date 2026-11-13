package com.emailagent.mailbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.logging.Log;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The owner's Google login for the Gmail mailbox: OAuth 2.0 for installed apps, authorization code with PKCE, answer
 * received on 127.0.0.1 only. The client file and the tokens live outside the project (~/.emailagent); the password
 * never reaches this code, the owner types it on Google's page.
 */
final class GoogleOAuth {

    static final String SCOPE = "https://www.googleapis.com/auth/gmail.modify";
    private static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(5);

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Path clientFile;
    private final Path tokenFile;
    private final String loginHint;
    private final SecureRandom random = new SecureRandom();

    private String accessToken;
    private Instant expiresAt = Instant.EPOCH;

    GoogleOAuth(ObjectMapper mapper, HttpClient http, Path credentialsDir, String loginHint) {
        this.mapper = mapper;
        this.http = http;
        this.clientFile = credentialsDir.resolve("gmail-client.json");
        this.tokenFile = credentialsDir.resolve("tokens").resolve("gmail.json");
        this.loginHint = loginHint;
    }

    /** A valid access token: cached, refreshed, or (first time, or after Google expired the login) a new login. */
    synchronized String accessToken() {
        if (accessToken != null && Instant.now().isBefore(expiresAt.minusSeconds(60))) {
            return accessToken;
        }
        String refresh = storedRefreshToken();
        if (refresh != null) {
            try {
                store(token(Map.of("grant_type", "refresh_token", "refresh_token", refresh)), refresh);
                return accessToken;
            } catch (IllegalStateException e) {
                // in Testing mode Google expires the login after about 7 days: log in again
                Log.warn("Gmail login expired, a new login is needed");
            }
        }
        login();
        return accessToken;
    }

    private record Client(String id, String secret, String authUri, String tokenUri) {}

    private Client client() {
        if (!Files.exists(clientFile)) {
            throw new IllegalStateException("Gmail client file missing: " + clientFile
                    + " (see the setup guide: download the Desktop OAuth client JSON there)");
        }
        tighten(clientFile); // it holds the client secret
        try {
            JsonNode installed = mapper.readTree(clientFile.toFile()).path("installed");
            return new Client(installed.path("client_id").asText(), installed.path("client_secret").asText(),
                    installed.path("auth_uri").asText("https://accounts.google.com/o/oauth2/auth"),
                    installed.path("token_uri").asText("https://oauth2.googleapis.com/token"));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + clientFile, e);
        }
    }

    /** Authorization code with PKCE; the browser comes back to a one-shot server on 127.0.0.1. */
    private void login() {
        Client c = client();
        String verifier = randomToken(48);
        String state = randomToken(24);
        CompletableFuture<Map<String, String>> answer = new CompletableFuture<>();
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open the local login port", e);
        }
        server.createContext("/", exchange -> {
            Map<String, String> query = parse(exchange.getRequestURI().getRawQuery());
            byte[] page = ("<html><body><p>Email agent: " + (query.containsKey("code") ? "login done, you can close "
                    + "this tab." : "login refused.") + "</p></body></html>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, page.length);
            exchange.getResponseBody().write(page);
            exchange.close();
            answer.complete(query);
        });
        server.start();
        String redirect = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("client_id", c.id());
            params.put("redirect_uri", redirect);
            params.put("response_type", "code");
            params.put("scope", SCOPE);
            params.put("code_challenge", challenge(verifier));
            params.put("code_challenge_method", "S256");
            params.put("state", state);
            params.put("access_type", "offline");
            params.put("prompt", "consent");
            if (loginHint != null) {
                params.put("login_hint", loginHint);
            }
            String url = c.authUri() + "?" + form(params);
            Log.infof("Gmail login: open this address in your browser if it doesn't open by itself:%n%s", url);
            openBrowser(url);
            Map<String, String> result = answer.get(LOGIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!state.equals(result.get("state")) || result.get("code") == null) {
                throw new IllegalStateException("Gmail login refused or tampered (state mismatch)");
            }
            JsonNode tokens = token(Map.of("grant_type", "authorization_code", "code", result.get("code"),
                    "redirect_uri", redirect, "code_verifier", verifier));
            store(tokens, tokens.path("refresh_token").asText(null));
            Log.info("Gmail login done");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gmail login interrupted", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("Gmail login not completed within " + LOGIN_TIMEOUT.toMinutes()
                    + " minutes", e);
        } finally {
            server.stop(0);
        }
    }

    private JsonNode token(Map<String, String> grant) {
        Client c = client();
        Map<String, String> body = new LinkedHashMap<>(grant);
        body.put("client_id", c.id());
        body.put("client_secret", c.secret());
        HttpRequest request = HttpRequest.newBuilder(URI.create(c.tokenUri())).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(body))).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // the body may hold Google's error code, never a token; keep only the code
                String error = mapper.readTree(response.body()).path("error").asText("http_" + response.statusCode());
                throw new IllegalStateException("Google token endpoint refused: " + error);
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException("Google token endpoint unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Package-private for tests. The refresh token is written readable by the owner only (SecretFiles). */
    void store(JsonNode tokens, String refreshToken) {
        accessToken = tokens.path("access_token").asText();
        expiresAt = Instant.now().plusSeconds(tokens.path("expires_in").asLong(3600));
        if (refreshToken != null) {
            try {
                ObjectNode saved = mapper.createObjectNode().put("refresh_token", refreshToken);
                SecretFiles.writeOwnerOnly(tokenFile, mapper.writeValueAsString(saved));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot save the Gmail token in " + tokenFile, e);
            }
        }
    }

    /** Package-private for tests. A token saved by an earlier version is made owner-only before it is read. */
    String storedRefreshToken() {
        if (!Files.exists(tokenFile)) {
            return null;
        }
        tighten(tokenFile);
        try {
            return mapper.readTree(tokenFile.toFile()).path("refresh_token").asText(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** Best effort: a file we can't restrict is still usable, and the warning tells the owner to fix it by hand. */
    private static void tighten(Path secret) {
        try {
            SecretFiles.restrictToOwner(secret);
        } catch (IOException e) {
            Log.warnf("could not make %s readable by the owner only (%s)", secret.getFileName(),
                    e.getClass().getSimpleName());
        }
    }

    private String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String challenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String form(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    static Map<String, String> parse(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    /** Opens the owner's default browser on Google's page (Windows); otherwise the logged address is enough. */
    private static void openBrowser(String url) {
        try {
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            }
        } catch (IOException e) {
            Log.info("could not open the browser; use the address logged above");
        }
    }
}
