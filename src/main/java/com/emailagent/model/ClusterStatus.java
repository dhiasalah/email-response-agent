package com.emailagent.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Cluster (input card) lifecycle (design doc §6.1). New questions join while OPEN or WAITING_INPUT; an empty
 * cluster keeps its definition until it expires, so late arrivals still match.
 */
public enum ClusterStatus {
    OPEN,
    WAITING_INPUT,
    ANSWERED,
    CLOSED;

    private static final Map<ClusterStatus, Set<ClusterStatus>> NEXT = new EnumMap<>(ClusterStatus.class);

    static {
        NEXT.put(OPEN, EnumSet.of(WAITING_INPUT, CLOSED));
        // closing a card still waiting: every member was resolved elsewhere (replied by hand, removed from the card)
        NEXT.put(WAITING_INPUT, EnumSet.of(ANSWERED, CLOSED));
        NEXT.put(ANSWERED, EnumSet.of(CLOSED));
        NEXT.put(CLOSED, EnumSet.noneOf(ClusterStatus.class));
    }

    public boolean acceptsMembers() {
        return this == OPEN || this == WAITING_INPUT;
    }

    public boolean canMoveTo(ClusterStatus next) {
        return next != null && NEXT.get(this).contains(next);
    }
}
