package com.emailagent.security;

import com.emailagent.mailbox.IncomingEmail;
import java.util.HashMap;
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
        String[] segments = normalize(header).split(";");
        String authServId = segments[0].strip().split(" ")[0];
        if (!authServId.equals(trustedAuthServId.toLowerCase(Locale.ROOT))) {
            return false;
        }
        boolean dmarcPass = false;
        boolean dkimAligned = false;
        for (int i = 1; i < segments.length; i++) {
            String[] tokens = segments[i].strip().split(" ");
            int eq = tokens[0].indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String method = tokens[0].substring(0, eq);
            String result = tokens[0].substring(eq + 1);
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
     * Comments removed (a server may quote attacker-chosen text in them), whitespace and folding collapsed, lower
     * case: header names, results and domains are case-insensitive.
     */
    private static String normalize(String header) {
        String s = header;
        String previous;
        do {
            previous = s;
            s = s.replaceAll("\\([^()]*\\)", " ");
        } while (!s.equals(previous));
        return s.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> properties(String[] tokens) {
        Map<String, String> props = new HashMap<>();
        for (int i = 1; i < tokens.length; i++) {
            int eq = tokens[i].indexOf('=');
            if (eq > 0) {
                props.putIfAbsent(tokens[i].substring(0, eq), tokens[i].substring(eq + 1).replace("\"", ""));
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
