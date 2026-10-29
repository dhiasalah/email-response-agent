package com.emailagent.config;

import io.quarkus.logging.LoggingFilter;
import java.util.logging.Filter;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;
import org.jboss.logmanager.ExtLogRecord;

/**
 * Last line for personal data in the logs (design doc §13, minimization). The code logs ids, codes and exception
 * types, never content; this filter still replaces any email address that slips into a log line (a Message-ID that
 * embeds one, a library's message), after formatting, so parameters can't bypass it.
 */
@LoggingFilter(name = "pii-redactor")
public final class PiiLogFilter implements Filter {

    static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");

    @Override
    public boolean isLoggable(LogRecord record) {
        if (record instanceof ExtLogRecord ext) {
            String formatted = ext.getFormattedMessage();
            if (formatted != null && EMAIL.matcher(formatted).find()) {
                ext.setMessage(redact(formatted), ExtLogRecord.FormatStyle.NO_FORMAT);
                ext.setParameters(null);
            }
        } else if (record.getMessage() != null) {
            record.setMessage(redact(record.getMessage()));
        }
        return true;
    }

    static String redact(String text) {
        return EMAIL.matcher(text).replaceAll("[email]");
    }
}
