package com.emailagent.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.emailagent.model.EmailStatus;
import com.emailagent.model.SlotOwner;
import com.emailagent.model.SlotStatus;
import com.emailagent.model.SlotType;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Wiring check: Flyway created the tables and Hibernate maps them (schema validated at start-up). */
@QuarkusTest
class DataModelSmokeTest {

    @Test
    @TestTransaction
    void emailWithSlotAndLogRoundTrips() {
        EmailEntity email = new EmailEntity();
        email.agentId = "student";
        email.messageId = "<smoke-1@univ.example>";
        email.sender = "lina@univ.example";
        email.subject = "Notes S2";
        email.receivedAt = Instant.now();
        email.persist();

        SlotEntity slot = new SlotEntity();
        slot.email = email;
        slot.type = SlotType.GRADES_PUBLICATION_DATE;
        slot.entity = "S2 exams";
        slot.owner = SlotOwner.USER;
        slot.persist();

        DecisionLogEntry.record(email.id, "smoke", "1 test email", "{}");
        EmailEntity.getEntityManager().flush();
        EmailEntity.getEntityManager().clear();

        EmailEntity loaded = EmailEntity.findByMessageId("<smoke-1@univ.example>").orElseThrow();
        assertThat(loaded.status).isEqualTo(EmailStatus.NEW);
        assertThat(loaded.slots).singleElement()
                .satisfies(s -> assertThat(s.status).isEqualTo(SlotStatus.UNRESOLVED));
        assertThat(loaded.scope().isKnown()).isFalse();
        assertThat(DecisionLogEntry.forEmail(loaded.id)).hasSize(1);
    }
}
