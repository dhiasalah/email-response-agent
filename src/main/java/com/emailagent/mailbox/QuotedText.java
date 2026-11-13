package com.emailagent.mailbox;

import java.util.regex.Pattern;

/**
 * The writer's own words in an email: everything above the quoted original, without "> " lines. Used for the owner's
 * sent replies (what the curator may learn from) and for a sender's answer to a clarification (what gets merged).
 */
public final class QuotedText {

    private static final Pattern QUOTE_HEADER = Pattern.compile(
            "(?im)^(on .{0,200} wrote:|le .{0,200} a [ée]crit ?:|-{2,}\\s*original message\\s*-{2,}|from:\\s.*|de\\s?:\\s.*)$");

    private QuotedText() {}

    public static String ownWords(String body) {
        if (body == null) {
            return "";
        }
        String text = body.replace("\r\n", "\n");
        var m = QUOTE_HEADER.matcher(text);
        if (m.find()) {
            text = text.substring(0, m.start());
        }
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) {
            if (!line.stripLeading().startsWith(">")) {
                out.append(line).append('\n');
            }
        }
        return out.toString().strip();
    }
}
