package com.emailagent.model;

/** Why a slot is on its card. The panel shows VERIFIED members apart: a model judged them, not an exact key. */
public enum GroupedBy {
    /** First question of a new shared card. */
    NEW,
    /** Same slot key and scope as the card: the same input by definition. */
    SAME_KEY,
    /** Different wording of the key, close embedding, and the verifier model said "same input". */
    VERIFIED,
    /** Personal or unknown-scope question: a card of its own that nothing else may join. */
    PERSONAL
}
