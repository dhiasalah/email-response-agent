package com.emailagent.store;

import com.emailagent.model.EmailStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    /** A short description, never the full email (personal data). */
    @Size(max = 1000)
    public String inputSummary;

    public String output;

    public Double confidence;

    @Size(max = 2000)
    public String sources;

    @Size(max = 100)
    public String modelVersion;

    /** The transition this step made; null for rows that don't change a status. */
    @Enumerated(EnumType.STRING) public EmailStatus fromStatus;
    @Enumerated(EnumType.STRING) public EmailStatus toStatus;

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

    /** A status change made by the orchestrator, with what the agent reported. */
    public static DecisionLogEntry transition(Long emailId, String agent, EmailStatus from, EmailStatus to,
            String output, Double confidence, String sources, String modelVersion) {
        DecisionLogEntry entry = new DecisionLogEntry();
        entry.emailId = emailId;
        entry.agent = agent;
        entry.inputSummary = "email " + emailId + " " + from + " -> " + to;
        entry.fromStatus = from;
        entry.toStatus = to;
        entry.output = output;
        entry.confidence = confidence;
        entry.sources = sources;
        entry.modelVersion = modelVersion;
        entry.createdAt = Instant.now();
        entry.persist();
        return entry;
    }

    public static List<DecisionLogEntry> forEmail(Long emailId) {
        return list("emailId = ?1 order by id", emailId);
    }
}
