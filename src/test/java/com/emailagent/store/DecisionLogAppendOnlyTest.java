package com.emailagent.store;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;

/** An attacker (or a bug) with SQL access tries to rewrite the audit trail. */
@QuarkusTest
class DecisionLogAppendOnlyTest {

    @Test
    @TestTransaction
    void rawSqlCannotRewriteHistory() {
        DecisionLogEntry.record(null, "reviewer", "draft for E42", "{\"pass\":false}");
        DecisionLogEntry.getEntityManager().flush();

        assertThatThrownBy(() -> DecisionLogEntry.getEntityManager()
                        .createNativeQuery("UPDATE decision_log SET output = '{\"pass\":true}'")
                        .executeUpdate())
                .isInstanceOf(PersistenceException.class)
                .rootCause().hasMessageContaining("append-only");
    }

    @Test
    @TestTransaction
    void rawSqlCannotDeleteHistory() {
        DecisionLogEntry.record(null, "reviewer", "draft for E42", "{\"pass\":false}");
        DecisionLogEntry.getEntityManager().flush();

        assertThatThrownBy(() -> DecisionLogEntry.getEntityManager()
                        .createNativeQuery("DELETE FROM decision_log")
                        .executeUpdate())
                .isInstanceOf(PersistenceException.class)
                .rootCause().hasMessageContaining("append-only");
    }
}
