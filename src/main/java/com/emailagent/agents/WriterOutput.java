package com.emailagent.agents;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What the writer model returns: the reply body, nothing else. It doesn't report which facts it used: code checks
 * that every fact is in the text, so the model's own claim about its sources would add nothing to trust.
 */
public record WriterOutput(@JsonProperty(required = true) @NotBlank @Size(max = 3000) String body) {}
