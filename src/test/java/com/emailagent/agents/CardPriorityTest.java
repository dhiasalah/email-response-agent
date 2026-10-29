package com.emailagent.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.emailagent.agents.CardPriority.Member;
import com.emailagent.agents.CardPriority.Weights;
import com.emailagent.model.SenderRole;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Card ranking (design doc §7.4): each factor bounded, and the ones a sender controls bounded harder. */
class CardPriorityTest {

    static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
    static final Weights W = new Weights(0.4, 0.3, 0.2, 0.1);

    static Member m(String sender, String text) {
        return new Member(sender, SenderRole.STUDENT, NOW.minus(Duration.ofHours(1)), text);
    }

    @Test
    void volumeCountsPeopleNotEmails() {
        List<Member> oneSenderTenTimes = new ArrayList<>();
        List<Member> tenSenders = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            oneSenderTenTimes.add(m("same@s.example", "When?"));
            tenSenders.add(m("s" + i + "@s.example", "When?"));
        }
        assertThat(CardPriority.volume(oneSenderTenTimes)).isEqualTo(0.1);
        assertThat(CardPriority.volume(tenSenders)).isEqualTo(1.0);
        assertThat(CardPriority.score(tenSenders, null, NOW, W))
                .isGreaterThan(CardPriority.score(oneSenderTenTimes, null, NOW, W));
    }

    @Test
    void urgentWordingIsCappedAndARealDateWins() {
        List<Member> shouting = List.of(m("a@s.example", "URGENT!!! réponse aujourd'hui, ASAP"));
        assertThat(CardPriority.urgency(shouting, null, NOW)).isEqualTo(0.5);
        String tomorrow = LocalDate.ofInstant(NOW, ZoneOffset.UTC).plusDays(1).toString();
        assertThat(CardPriority.urgency(List.of(m("a@s.example", "When?")), tomorrow, NOW)).isEqualTo(1.0);
        // half the senders shouting: half the capped wording urgency
        assertThat(CardPriority.wordingUrgency(List.of(m("a@s.example", "urgent"), m("b@s.example", "when?"))))
                .isEqualTo(0.25);
    }

    @Test
    void datesAreReadInTheFormatsOurSourcesUse() {
        assertThat(CardPriority.parseDate("2027-06-18")).isEqualTo(LocalDate.of(2027, 6, 18));
        assertThat(CardPriority.parseDate("18 June 2027")).isEqualTo(LocalDate.of(2027, 6, 18));
        assertThat(CardPriority.parseDate("18 juin 2027")).isEqualTo(LocalDate.of(2027, 6, 18));
        assertThat(CardPriority.parseDate("18/06/2027")).isEqualTo(LocalDate.of(2027, 6, 18));
        assertThat(CardPriority.parseDate("room A101")).isNull();
        assertThat(CardPriority.dateUrgency("2026-10-01", NOW)).isZero(); // past: stale, not urgent
    }

    @Test
    void roleComesFromTheRosterAndAgeSaturates() {
        Member head = new Member("head@u.example", SenderRole.MANAGEMENT, NOW, "q");
        Member nobody = new Member("x@evil.example", SenderRole.UNKNOWN, NOW, "q");
        assertThat(CardPriority.senderWeight(List.of(head, nobody))).isEqualTo(1.0);
        assertThat(CardPriority.senderWeight(List.of(nobody))).isZero();
        Member old = new Member("a@s.example", SenderRole.STUDENT, NOW.minus(Duration.ofDays(10)), "q");
        assertThat(CardPriority.age(List.of(old), NOW)).isEqualTo(1.0);
    }

    @Test
    void scoreStaysBetweenZeroAndOne() {
        List<Member> max = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            max.add(new Member("s" + i + "@u.example", SenderRole.MANAGEMENT, NOW.minus(Duration.ofDays(5)), "urgent"));
        }
        String tomorrow = LocalDate.ofInstant(NOW, ZoneOffset.UTC).plusDays(1).toString();
        assertThat(CardPriority.score(max, tomorrow, NOW, W)).isEqualTo(1.0);
        assertThat(CardPriority.score(List.of(), tomorrow, NOW, W)).isZero();
    }
}
