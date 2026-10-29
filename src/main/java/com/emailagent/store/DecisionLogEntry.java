package com.emailagent.store;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.Immutable;

/** One row per agent step (design doc §5). Insert-only: Hibernate never updates it, the database refuses to. */
@Entity
@Immutable
@Table(name = "decision_log")
public class DecisionLogEntry extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public Long emailId;

    @NotBlank @Size(max = 50)
    public String agent;

    /** A short description, never the full email (personal data, lesson 16). */
    @Size(max = 1000)
    public String inputSummary;

    public String output;

    public Double confidence;

    @Size(max = 2000)
    public String sources;

    @Size(max = 100)
    public String modelVersion;

    @NotNull
    public Instant createdAt;

    public static DecisionLogEntry record(Long emailId, String agent, String inputSummary, String output) {
        DecisionLogEntry entry = new DecisionLogEntry();
        entry.emailId = emailId;
        entry.agent = agent;
        entry.inputSummary = inputSummary;
        entry.output = output;
        entry.createdAt = Instant.now();
        entry.persist();
        return entry;
    }

    public static List<DecisionLogEntry> forEmail(Long emailId) {
        return list("emailId = ?1 order by id", emailId);
    }
}
