package com.emailagent.model;

/**
 * The owner's choice on a card for reuse (design doc §7.3, §8.2). Never wider than the card's own audience:
 * a partial scope match ("all Master 2" for an M2 IA card) is not reuse, so it isn't offered.
 */
public enum ScopeChoice {
    /** Answer the emails on the card, store nothing for later senders. */
    THESE_EMAILS,
    /** Also store a fact for the card's exact audience (program, track, year from the roster). */
    CARD_AUDIENCE
}
