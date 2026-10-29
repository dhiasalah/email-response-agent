package com.emailagent.store;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** A sent reply already learned from: each reply is read (and sent to the model) once. */
@Entity
@Table(name = "processed_reply")
public class ProcessedReplyEntity extends PanacheEntityBase {

    @Id
    public String messageId;

    public Long emailId;

    public int pairs;

    @NotNull
    public Instant processedAt;

    public static void mark(String messageId, Long emailId, int pairs, Instant now) {
        ProcessedReplyEntity p = new ProcessedReplyEntity();
        p.messageId = messageId;
        p.emailId = emailId;
        p.pairs = pairs;
        p.processedAt = now;
        p.persist();
    }
}
