package com.emailagent.agents;

import com.emailagent.model.ReviewIssue;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * What the reviewer model returns: the problems it found (closed list) and a verdict. Issues come first so the
 * model names the problems before it decides. Code reads PASS only when the list is empty too.
 */
public record ReviewerOutput(
        @JsonProperty(required = true) @NotNull @Size(max = 6) List<@NotNull ReviewIssue> issues,
        @JsonProperty(required = true) @NotNull Verdict verdict) {

    public enum Verdict {
        PASS,
        FAIL
    }
}
