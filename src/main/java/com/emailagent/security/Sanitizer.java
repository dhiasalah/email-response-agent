package com.emailagent.security;

import jakarta.enterprise.context.ApplicationScoped;
import java.text.Normalizer;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Turns an untrusted email body into plain text a model can read, removing what a human reader would not see:
 * hidden HTML, invisible Unicode, control characters. What it removed becomes a flag (a signal for the detector).
 */
@ApplicationScoped
public class Sanitizer {

    public static final int MAX_BODY_CHARS = 20_000;

    /** Clean text plus what was found. Flags are sorted names like "hidden_html". */
    public record Sanitized(String text, Set<String> flags) {}

    private static final Pattern HIDDEN_STYLE = Pattern.compile(
            "display:none|visibility:hidden|opacity:0(\\.0+)?(;|$)|font-size:0(px|pt|em|rem|%)?(;|$)"
                    + "|(max-)?height:0(px)?(;|$)|color:(#fff(fff)?|white|rgb\\(255,255,255\\))(;|$)"
                    + "|text-indent:-\\d{3,}|(left|top):-\\d{3,}");
    private static final Pattern BIDI = Pattern.compile("[\\u202A-\\u202E\\u2066-\\u2069]");
    private static final Pattern INVISIBLE = Pattern.compile("\\p{Cf}");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");
    private static final Pattern MIXED_SCRIPT_WORD = Pattern.compile(
            "(?=\\S*\\p{IsLatin})(?=\\S*[\\p{IsCyrillic}\\p{IsGreek}])\\S+");

    public Sanitized clean(String html, String text) {
        Set<String> flags = new TreeSet<>();
        String plain = html != null && !html.isBlank() ? htmlToText(html, flags) : (text == null ? "" : text);
        return new Sanitized(normalize(plain, flags, MAX_BODY_CHARS), flags);
    }

    /** For one-line fields (subject, display name): no HTML, same Unicode rules, one line. */
    public Sanitized cleanLine(String value, int maxChars) {
        Set<String> flags = new TreeSet<>();
        String line = value == null ? "" : value.replaceAll("[\\r\\n]+", " ");
        return new Sanitized(normalize(line, flags, maxChars), flags);
    }

    private static String htmlToText(String html, Set<String> flags) {
        Document doc = Jsoup.parse(html);
        doc.select("script, style, head, title, meta, link, noscript, template, iframe, object, embed").remove();
        for (Element el : doc.body().select("*")) {
            if (el.ownerDocument() == null) {
                continue; // inside an element already removed
            }
            if (isHidden(el)) {
                el.remove();
                flags.add("hidden_html");
            }
        }
        doc.outputSettings().prettyPrint(false);
        doc.select("p, div, li, tr, h1, h2, h3, h4, h5, h6, blockquote").before("\n");
        return doc.body().wholeText();
    }

    private static boolean isHidden(Element el) {
        if (el.hasAttr("hidden") || "true".equalsIgnoreCase(el.attr("aria-hidden"))) {
            return true;
        }
        String style = el.attr("style").toLowerCase().replaceAll("\\s+", "");
        return !style.isEmpty() && HIDDEN_STYLE.matcher(style).find();
    }

    private static String normalize(String s, Set<String> flags, int maxChars) {
        if (BIDI.matcher(s).find()) {
            flags.add("bidi_control");
        }
        // NFKC folds look-alike forms (fullwidth letters, ligatures, superscripts) into plain ones
        String out = Normalizer.normalize(s, Normalizer.Form.NFKC);
        if (INVISIBLE.matcher(out).find()) {
            flags.add("invisible_unicode");
            out = INVISIBLE.matcher(out).replaceAll("");
        }
        out = CONTROL.matcher(out).replaceAll("");
        if (MIXED_SCRIPT_WORD.matcher(out).find()) {
            flags.add("mixed_scripts");
        }
        out = out.replaceAll("[ \\t\\x{00A0}]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").strip();
        if (out.length() > maxChars) {
            flags.add("truncated");
            out = out.substring(0, maxChars);
        }
        return out;
    }
}
