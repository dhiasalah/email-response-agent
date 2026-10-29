package com.emailagent.store;

import com.emailagent.model.SlotOwner;
import com.emailagent.model.SlotStatus;
import com.emailagent.model.SlotType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Entity
@Table(name = "slot")
public class SlotEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    public EmailEntity email;

    @NotNull @Enumerated(EnumType.STRING)
    public SlotType type;

    @NotBlank @Size(max = 200)
    public String entity;

    @Size(max = 200)
    public String qualifier;

    @NotNull @Enumerated(EnumType.STRING)
    public SlotOwner owner;

    @NotNull @Enumerated(EnumType.STRING)
    public SlotStatus status = SlotStatus.UNRESOLVED;

    @Size(max = 2000)
    public String value;

    @Size(max = 500)
    public String source;

    public Long requestId;

    /** Sets value and source together: a value without a source is refused by the database. */
    public void fill(String value, String source, SlotStatus status) {
        this.value = value;
        this.source = source;
        this.status = status;
    }
}
