package com.gighub.work.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkCasePolicyTest {

    private static final LocalDateTime STARTS_AT =
            LocalDateTime.of(2026, 8, 10, 18, 0);

    @ParameterizedTest
    @MethodSource("allTransitionPairs")
    void transitionTableAllowsOnlyDeclaredLifecyclePairs(
            WorkCaseStatus current,
            WorkCaseStatus target,
            WorkCaseDecision expected) {
        assertEquals(expected, WorkCasePolicy.decideTransition(current, target));
    }

    @Test
    void invitationCommandsRequireDraftUnassignedFutureWork() {
        LocalDateTime beforeStart = STARTS_AT.minusHours(1);

        assertEquals(
                WorkCaseDecision.ALLOWED,
                WorkCasePolicy.decideInvitationIssue(
                        WorkCaseStatus.DRAFT, null, STARTS_AT, beforeStart));
        assertEquals(
                WorkCaseDecision.STATUS_NOT_DRAFT,
                WorkCasePolicy.decideInvitationAccept(
                        WorkCaseStatus.ACCEPTED, null, STARTS_AT, beforeStart));
        assertEquals(
                WorkCaseDecision.WORKER_ALREADY_ASSIGNED,
                WorkCasePolicy.decideInvitationAccept(
                        WorkCaseStatus.DRAFT, 7L, STARTS_AT, beforeStart));
        assertEquals(
                WorkCaseDecision.WORK_ALREADY_STARTED,
                WorkCasePolicy.decideInvitationAccept(
                        WorkCaseStatus.DRAFT, null, STARTS_AT, STARTS_AT));
    }

    @Test
    void draftMutationHasAnExplicitDecision() {
        assertEquals(
                WorkCaseDecision.ALLOWED,
                WorkCasePolicy.decideDraftMutation(WorkCaseStatus.DRAFT));
        assertEquals(
                WorkCaseDecision.STATUS_NOT_DRAFT,
                WorkCasePolicy.decideDraftMutation(WorkCaseStatus.ACCEPTED));
    }

    private static Stream<Arguments> allTransitionPairs() {
        Set<String> allowed = Set.of(
                "DRAFT->ACCEPTED",
                "DRAFT->CANCELED",
                "ACCEPTED->READY",
                "ACCEPTED->NO_SHOW",
                "ACCEPTED->COMPLETED",
                "READY->IN_PROGRESS",
                "READY->NO_SHOW",
                "READY->COMPLETED",
                "IN_PROGRESS->COMPLETED",
                "IN_PROGRESS->CHECK_OUT_MISSING");
        return Stream.of(WorkCaseStatus.values())
                .flatMap(current -> Stream.of(WorkCaseStatus.values())
                        .map(target -> Arguments.of(
                                current,
                                target,
                                allowed.contains(current + "->" + target)
                                        ? WorkCaseDecision.ALLOWED
                                        : WorkCaseDecision.TRANSITION_NOT_ALLOWED)));
    }
}
