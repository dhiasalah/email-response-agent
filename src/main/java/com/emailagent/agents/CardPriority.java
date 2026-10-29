package com.emailagent.agents;

import com.emailagent.config.AppConfig;
import com.emailagent.model.SenderRole;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Card ranking (design doc §7.4): {@code w1·volume + w2·urgency + w3·age + w4·sender}, each factor in 0..1.
 * Plain code, so the owner can be told exactly why a card is on top. Senders choose their words, so the factors
 * they control are bounded: volume counts distinct senders, urgent wording alone reaches at most half the urgency.
 */
public final class CardPriority {

    /** One question on the card, as priority sees it. {@code role} comes from the roster, never from the email. */
    public record Member(String sender, SenderRole role, Instant receivedAt, String text) {}

    public record Weights(double urgency, double volume, double age, double sender) {

        public static Weights of(AppConfig config) {
            return new Weights(config.priorityWeightUrgency(), config.priorityWeightVolume(),
                    config.priorityWeightAge(), config.priorityWeightSender());
        }

        double sum() {
            return urgency + volume + age + sender;
        }
    }

    /** Ten different people waiting is "a lot" for one professor; more doesn't raise the score further. */
    static final int FULL_VOLUME = 10;
    static final Duration FULL_AGE = Duration.ofHours(72);
    static final double MAX_WORDING_URGENCY = 0.5;

    static final Set<String> URGENT_WORDS = Set.of("urgent", "asap", "as soon as possible", "today", "tomorrow",
            "aujourd'hui", "demain", "au plus vite", "rapidement", "des que possible");

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"));

    private CardPriority() {}

    public static double score(List<Member> members, String proposedValue, Instant now, Weights w) {
        if (members.isEmpty() || w.sum() <= 0) {
            return 0;
        }
        double raw = w.urgency() * urgency(members, proposedValue, now)
                + w.volume() * volume(members)
                + w.age() * age(members, now)
                + w.sender() * senderWeight(members);
        return Math.round(Math.min(1, raw / w.sum()) * 1000) / 1000.0;
    }

    static double volume(List<Member> members) {
        long distinct = members.stream().map(m -> m.sender().toLowerCase(Locale.ROOT)).distinct().count();
        return Math.min(1.0, (double) distinct / FULL_VOLUME);
    }

    /** A date the card is about (from a trusted proposal) beats any wording; wording is capped. */
    static double urgency(List<Member> members, String proposedValue, Instant now) {
        return Math.max(dateUrgency(proposedValue, now), wordingUrgency(members));
    }

    static double dateUrgency(String value, Instant now) {
        LocalDate date = parseDate(value);
        if (date == null) {
            return 0;
        }
        long days = Duration.between(now, date.atStartOfDay().toInstant(ZoneOffset.UTC)).toDays();
        if (days < 0) {
            return 0; // already past: the card is stale, not urgent
        }
        return days <= 1 ? 1.0 : days <= 3 ? 0.8 : days <= 7 ? 0.5 : 0.2;
    }

    /** Share of distinct senders who used urgent words, times the cap: one loud sender can't fill the queue top. */
    static double wordingUrgency(List<Member> members) {
        Set<String> senders = members.stream().map(m -> m.sender().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        Set<String> urgent = members.stream().filter(m -> isUrgent(m.text()))
                .map(m -> m.sender().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        return MAX_WORDING_URGENCY * urgent.size() / senders.size();
    }

    static double age(List<Member> members, Instant now) {
        Instant oldest = members.stream().map(Member::receivedAt).min(Instant::compareTo).orElse(now);
        double hours = Math.max(0, Duration.between(oldest, now).toMinutes() / 60.0);
        return Math.min(1.0, hours / FULL_AGE.toHours());
    }

    static double senderWeight(List<Member> members) {
        return members.stream().mapToDouble(m -> m.role().weight()).max().orElse(0);
    }

    static boolean isUrgent(String text) {
        if (text == null) {
            return false;
        }
        String folded = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return URGENT_WORDS.stream().anyMatch(folded::contains);
    }

    static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(value.strip(), f);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        return null;
    }
}
