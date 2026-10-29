package com.emailagent.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.emailagent.model.EmailStatus;
import com.emailagent.model.FilterReason;
import com.emailagent.store.EmailEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The database trigger (V0501) and {@link EmailLifecycle} hold the same table. Raw SQL (a bug, an injected query,
 * a later panel) gets exactly the transitions the Java code allows, no more. Every attempt is rolled back.
 */
@QuarkusTest
class LifecycleDatabaseGuardTest {

    @Test
    void databaseAllowsExactlyTheLifecycleTransitions() {
        List<String> disagreements = new ArrayList<>();
        for (EmailStatus from : EmailStatus.values()) {
            for (EmailStatus to : EmailStatus.values()) {
                if (from == to) {
                    continue;
                }
                boolean accepted = databaseAccepts(from, to);
                if (accepted != EmailLifecycle.allowed(from, to)) {
                    disagreements.add(from + " -> " + to + (accepted ? " accepted" : " refused"));
                }
            }
        }
        assertThat(disagreements).isEmpty();
    }

    private static boolean databaseAccepts(EmailStatus from, EmailStatus to) {
        QuarkusTransaction.begin();
        try {
            EmailEntity email = new EmailEntity();
            email.messageId = "<guard-" + from + "-" + to + "@test>";
            email.sender = "guard@univ.example";
            email.receivedAt = Instant.now();
            email.status = from;
            email.filterReason = from == EmailStatus.FILTERED_OUT ? FilterReason.AUTO_REPLY : null;
            email.persist();
            EmailEntity.getEntityManager().flush();
            try {
                EmailEntity.getEntityManager()
                        .createNativeQuery("UPDATE email SET status = ?1, filter_reason = ?2 WHERE id = ?3")
                        .setParameter(1, to.name())
                        .setParameter(2, to == EmailStatus.FILTERED_OUT ? "AUTO_REPLY" : null)
                        .setParameter(3, email.id)
                        .executeUpdate();
                return true;
            } catch (PersistenceException e) {
                assertThat(rootMessage(e)).contains("illegal status transition");
                return false;
            }
        } finally {
            QuarkusTransaction.rollback(); // leave no rows behind
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage();
    }
}
