package com.emailagent.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Cluster lifecycle (design doc §6.1). */
class ClusterStatusTest {

    @Test
    void onlyOpenOrWaitingCardsAcceptQuestions() {
        assertThat(ClusterStatus.OPEN.acceptsMembers()).isTrue();
        assertThat(ClusterStatus.WAITING_INPUT.acceptsMembers()).isTrue();
        assertThat(ClusterStatus.ANSWERED.acceptsMembers()).isFalse(); // later questions use the fact instead
        assertThat(ClusterStatus.CLOSED.acceptsMembers()).isFalse();
    }

    @Test
    void transitionsFollowTheDesign() {
        assertThat(ClusterStatus.OPEN.canMoveTo(ClusterStatus.WAITING_INPUT)).isTrue();
        assertThat(ClusterStatus.WAITING_INPUT.canMoveTo(ClusterStatus.ANSWERED)).isTrue();
        assertThat(ClusterStatus.ANSWERED.canMoveTo(ClusterStatus.CLOSED)).isTrue();
        assertThat(ClusterStatus.OPEN.canMoveTo(ClusterStatus.ANSWERED)).isFalse(); // answered means shown first
        assertThat(ClusterStatus.CLOSED.canMoveTo(ClusterStatus.OPEN)).isFalse();
        assertThat(ClusterStatus.ANSWERED.canMoveTo(ClusterStatus.WAITING_INPUT)).isFalse();
    }
}
