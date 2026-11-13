package com.emailagent.llm;

/** Model size per agent (design doc §4): FAST for the classifier, resolver, grouping, curator; STRONG for writer, reviewer. */
public enum ModelTier {
    FAST,
    STRONG
}
