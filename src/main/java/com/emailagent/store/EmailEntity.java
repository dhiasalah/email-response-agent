package com.emailagent.store;

import com.emailagent.model.EmailStatus;
import com.emailagent.model.FilterReason;
import com.emailagent.model.Intent;
import com.emailagent.model.Language;
import com.emailagent.model.SenderScope;
import com.emailagent.model.Sensitivity;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Entity
@Table(name = "email")
public class EmailEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @NotBlank @Size(max = 998)
    public String messageId;

    @Size(max = 998)
    public String threadId;

    @NotBlank @Email @Size(max = 320)
    public String sender;

    /** Display name as written by the sender: untrusted, can impersonate ("Prof. Martin"). */
    @Size(max = 200)
    public String senderName;

    @Size(max = 998)
    public String subject;

    @Size(max = 50_000)
    public String body;

    @NotNull
    public Instant receivedAt;

    @Size(max = 100) public String scopeProgram;
    @Size(max = 100) public String scopeTrack;
    @Size(max = 20) public String scopeYear;

    @Enumerated(EnumType.STRING) public Intent intent;
    @Enumerated(EnumType.STRING) public Sensitivity sensitivity;
    @Enumerated(EnumType.STRING) public Language language;

    @NotNull @Enumerated(EnumType.STRING)
    public EmailStatus status = EmailStatus.NEW;

    /** What the sanitizer removed or noticed (hidden_html, invisible_unicode…): signals for the detector. */
    @Size(max = 500)
    public String sanitizerFlags;

    @Enumerated(EnumType.STRING)
    public FilterReason filterReason;

    /** The classifier's answer to "does this need a reply?". Null until classified. */
    public Boolean replyNeeded;

    /** Injection detector result: the score and the signals that produced it. */
    public Double injectionScore;

    @Size(max = 500)
    public String injectionSignals;

    /** The sender answered a clarification and the answer was merged into this email; classification must run again. */
    public boolean senderReplied;

    /** Set when retention or an erasure request reduced this row to a tombstone (design doc §13). */
    public Instant erasedAt;

    @Version
    public long version;

    public Instant updatedAt;

    @OneToMany(mappedBy = "email")
    public List<SlotEntity> slots = new ArrayList<>();

    public SenderScope scope() {
        return new SenderScope(scopeProgram, scopeTrack, scopeYear);
    }

    /**
     * The stored form of an erased email's Message-ID: retention writes it, and the importer compares new mail against
     * it so an erased email is never imported again.
     */
    public static String erasedMessageId(String messageId) {
        if (messageId == null || messageId.startsWith("erased:")) {
            return messageId;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(messageId.getBytes(StandardCharsets.UTF_8));
            return "erased:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static Optional<EmailEntity> findByMessageId(String messageId) {
        return find("messageId", messageId).firstResultOptional();
    }

    public static List<EmailEntity> listByStatus(EmailStatus status) {
        return list("status", status);
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
