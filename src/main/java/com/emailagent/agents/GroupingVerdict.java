package com.emailagent.agents;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * What the grouping verifier must return: one boolean. Nothing else to parse means nothing else to inject;
 * the worst a hijacked verifier can do is say "true", and code gates already bound what "true" can join.
 */
public record GroupingVerdict(@JsonProperty(required = true) @NotNull Boolean sameInput) {}
