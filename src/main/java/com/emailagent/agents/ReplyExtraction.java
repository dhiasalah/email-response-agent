package com.emailagent.agents;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * What the curator's model must return for a sent reply: which numbered question was answered, and the answer as
 * written. Bounded; the code then checks every answer against the owner's own text.
 */
public record ReplyExtraction(
        @JsonProperty(required = true) @NotNull @Size(max = 5) List<@NotNull @Valid Answer> answers) {

    public record Answer(
            @JsonProperty(required = true) @NotNull @Min(1) @Max(5) Integer question,
            @JsonProperty(required = true) @NotNull @Size(max = 200) String answer) {}
}
