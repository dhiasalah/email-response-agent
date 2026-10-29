package com.emailagent.store;

import com.emailagent.model.EmailStatus;
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
import java.time.Instant;
import java.util.ArrayList;
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

    @Version
    public long version;

    public Instant updatedAt;

    @OneToMany(mappedBy = "email")
    public List<SlotEntity> slots = new ArrayList<>();

    public SenderScope scope() {
        return new SenderScope(scopeProgram, scopeTrack, scopeYear);
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
