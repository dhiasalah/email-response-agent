package com.emailagent.agents;

import com.emailagent.model.Intent;
import com.emailagent.model.Language;
import com.emailagent.model.Sensitivity;
import com.emailagent.model.SlotOwner;
import com.emailagent.model.SlotType;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * What the classifier model must return. Closed enums (the taxonomy), bounded sizes, every field required:
 * the strict parser refuses anything else, so a hijacked model can't add a field or invent a slot type.
 */
public record ClassifierOutput(
        @JsonProperty(required = true) @NotNull Intent intent,
        @JsonProperty(required = true) @NotNull Boolean replyNeeded,
        @JsonProperty(required = true) @NotNull Sensitivity sensitivity,
        @JsonProperty(required = true) @NotNull Language language,
        @JsonProperty(required = true) @NotNull @Size(max = 5) List<@NotNull @Valid Question> questions,
        @JsonProperty(required = true) @NotNull Boolean suspicious) {

    /** One distinct question and the slot that answers it: the unit grouping works on. */
    public record Question(
            @JsonProperty(required = true) @NotBlank @Size(max = 300) String text,
            @JsonProperty(required = true) @NotNull SlotType slotType,
            @JsonProperty(required = true) @NotBlank @Size(max = 100) String entity,
            @JsonProperty(required = true) @NotNull @Size(max = 100) String qualifier,
            @JsonProperty(required = true) @NotNull SlotOwner owner) {}
}
