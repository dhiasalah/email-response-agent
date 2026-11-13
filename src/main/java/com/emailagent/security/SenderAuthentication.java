package com.emailagent.security;

import com.emailagent.model.IncomingEmail;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Whether the receiving server vouched for the sender's address (design doc §9 sender rules, §15 "sender scope").
 * The From header is free text: anyone can write a student's address there. Only the receiving server's
 * Authentication-Results (RFC 8601) ties it to a real mailbox: a DMARC pass for the From domain, or a DKIM signature
 * from that domain or a parent of it. Plain code, no model; anything less is "not authenticated".
 */
public final class SenderAuthentication {

    /** Added to the sanitizer flags of a message whose sender could not be authenticated. */
    public static final String UNVERIFIED_FLAG = "sender_unverified";

    private SenderAuthentication() {}

    /** True when the stored sanitizer flags (comma-separated) say the sender wasn't authenticated. */
    public static boolean flagged(String sanitizerFlags) {
        return sanitizerFlags != null && List.of(sanitizerFlags.split(",")).contains(UNVERIFIED_FLAG);
    }

    /**
     * @param trustedAuthServId the receiving server's name at the start of its header ("mx.google.com"): a header
     *     written by anyone else is ignored
     */
    public static boolean isAuthenticated(IncomingEmail email, String trustedAuthServId) {
        String header = email.header("Authentication-Results");
        String fromDomain = domainOf(email.from());
        if (header == null || fromDomain == null || trustedAuthServId == null) {
            return false;
        }
        List<List<String>> segments = tokenize(header);
        if (segments == null || segments.get(0).isEmpty()
                || !segments.get(0).get(0).equals(trustedAuthServId.toLowerCase(Locale.ROOT))) {
            return false;
        }
        boolean dmarcPass = false;
        boolean dkimAligned = false;
        for (List<String> tokens : segments.subList(1, segments.size())) {
            int eq = tokens.isEmpty() ? -1 : tokens.get(0).indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String method = tokens.get(0).substring(0, eq);
            String result = tokens.get(0).substring(eq + 1);
            Map<String, String> props = properties(tokens);
            if (method.equals("dmarc") && result.equals("fail")) {
                return false; // the domain's own policy says no: nothing else outweighs it
            }
            if (method.equals("dmarc") && result.equals("pass") && fromDomain.equals(props.get("header.from"))) {
                dmarcPass = true;
            }
            if (method.equals("dkim") && result.equals("pass")) {
                String signer = props.containsKey("header.d") ? props.get("header.d") : domainOf(props.get("header.i"));
                dkimAligned |= aligned(signer, fromDomain);
            }
        }
        return dmarcPass || dkimAligned;
    }

    /**
     * The header as segments (split on ";") of tokens (split on whitespace), lower case, read the way RFC 8601 writes
     * it: a quoted string is one opaque piece of a token, whatever it holds, and a comment is dropped. The receiving
     * server echoes values the sender chose (the envelope sender, the HELO name, a DKIM identity), so a ";" or a
     * "(" inside them must never start a result of its own. Null when the header is malformed: an unbalanced quote or
     * comment, or a comment glued to the text before it, which a server never writes but an injected HELO name
     * (no spaces allowed) needs to separate its fake properties.
     */
    static List<List<String>> tokenize(String header) {
        List<List<String>> segments = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        int depth = 0;
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (quoted) {
                if (c == '\\' && i + 1 < header.length()) {
                    token.append(header.charAt(++i));
                } else if (c == '"') {
                    quoted = false;
                } else {
                    token.append(c);
                }
            } else if (depth > 0) {
                if (c == '\\') {
                    i++;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == '(') {
                char before = i == 0 ? ' ' : header.charAt(i - 1);
                if (!Character.isWhitespace(before) && before != ';') {
                    return null;
                }
                depth = 1;
            } else if (c == ')') {
                return null;
            } else if (c == ';') {
                endToken(token, tokens);
                segments.add(tokens);
                tokens = new ArrayList<>();
            } else if (Character.isWhitespace(c)) {
                endToken(token, tokens);
            } else {
                token.append(c);
            }
        }
        if (quoted || depth != 0) {
            return null;
        }
        endToken(token, tokens);
        segments.add(tokens);
        return segments;
    }

    private static void endToken(StringBuilder token, List<String> tokens) {
        if (!token.isEmpty()) {
            tokens.add(token.toString().toLowerCase(Locale.ROOT));
            token.setLength(0);
        }
    }

    private static Map<String, String> properties(List<String> tokens) {
        Map<String, String> props = new HashMap<>();
        for (String t : tokens.subList(1, tokens.size())) {
            int eq = t.indexOf('=');
            if (eq > 0) {
                props.putIfAbsent(t.substring(0, eq), t.substring(eq + 1));
            }
        }
        return props;
    }

    /** The signer is the From domain or a parent of it, at a dot: "univ.example" signs for "student.univ.example". */
    private static boolean aligned(String signer, String fromDomain) {
        return signer != null && !signer.isEmpty()
                && (fromDomain.equals(signer) || fromDomain.endsWith("." + signer));
    }

    /** The part after the last "@", lower case; null if there is none. */
    private static String domainOf(String address) {
        if (address == null) {
            return null;
        }
        int at = address.lastIndexOf('@');
        String domain = at >= 0 ? address.substring(at + 1).strip().toLowerCase(Locale.ROOT) : "";
        return domain.isEmpty() ? null : domain;
    }
}
