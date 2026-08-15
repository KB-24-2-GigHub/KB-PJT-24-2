package com.gighub.work.domain;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Work Case의 허용 명령과 상태 전이표를 한곳에서 결정합니다.
 *
 * <p>이 Policy는 HTTP 오류나 Mapper를 알지 못합니다. Application Service는 결과를 기존 API
 * 오류로 바꾸고, Mapper는 허용된 expected-state 쌍을 조건부 UPDATE로 다시 방어합니다.</p>
 */
public final class WorkCasePolicy {

    private static final Map<WorkCaseStatus, Set<WorkCaseStatus>> TRANSITIONS = transitions();

    private WorkCasePolicy() {
    }

    /** 조건 수정·삭제처럼 DRAFT에서만 가능한 명령을 판정합니다. */
    public static WorkCaseDecision decideDraftMutation(WorkCaseStatus status) {
        return status == WorkCaseStatus.DRAFT
                ? WorkCaseDecision.ALLOWED
                : WorkCaseDecision.STATUS_NOT_DRAFT;
    }

    /** OWNER가 현재 조건으로 초대를 발급할 수 있는지 판정합니다. */
    public static WorkCaseDecision decideInvitationIssue(
            WorkCaseStatus status,
            Long workerId,
            LocalDateTime startsAt,
            LocalDateTime now) {
        return decideInvitationCommand(status, workerId, startsAt, now);
    }

    /** WORKER가 수락 시점의 최신 Work Case를 확정할 수 있는지 판정합니다. */
    public static WorkCaseDecision decideInvitationAccept(
            WorkCaseStatus status,
            Long workerId,
            LocalDateTime startsAt,
            LocalDateTime now) {
        return decideInvitationCommand(status, workerId, startsAt, now);
    }

    /** expected-state UPDATE에 전달할 상태 쌍이 제품 lifecycle에 있는지 판정합니다. */
    public static WorkCaseDecision decideTransition(
            WorkCaseStatus current,
            WorkCaseStatus target) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(target, "target");
        return TRANSITIONS.getOrDefault(current, Set.of()).contains(target)
                ? WorkCaseDecision.ALLOWED
                : WorkCaseDecision.TRANSITION_NOT_ALLOWED;
    }

    private static WorkCaseDecision decideInvitationCommand(
            WorkCaseStatus status,
            Long workerId,
            LocalDateTime startsAt,
            LocalDateTime now) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(now, "now");
        if (workerId != null) {
            return WorkCaseDecision.WORKER_ALREADY_ASSIGNED;
        }
        if (status != WorkCaseStatus.DRAFT) {
            return WorkCaseDecision.STATUS_NOT_DRAFT;
        }
        if (!now.isBefore(startsAt)) {
            return WorkCaseDecision.WORK_ALREADY_STARTED;
        }
        return WorkCaseDecision.ALLOWED;
    }

    private static Map<WorkCaseStatus, Set<WorkCaseStatus>> transitions() {
        EnumMap<WorkCaseStatus, Set<WorkCaseStatus>> result =
                new EnumMap<>(WorkCaseStatus.class);
        result.put(
                WorkCaseStatus.DRAFT,
                Set.of(WorkCaseStatus.ACCEPTED, WorkCaseStatus.CANCELED));
        result.put(
                WorkCaseStatus.ACCEPTED,
                Set.of(
                        WorkCaseStatus.READY,
                        WorkCaseStatus.NO_SHOW,
                        WorkCaseStatus.COMPLETED));
        result.put(
                WorkCaseStatus.READY,
                Set.of(
                        WorkCaseStatus.IN_PROGRESS,
                        WorkCaseStatus.NO_SHOW,
                        WorkCaseStatus.COMPLETED));
        result.put(
                WorkCaseStatus.IN_PROGRESS,
                Set.of(WorkCaseStatus.COMPLETED, WorkCaseStatus.CHECK_OUT_MISSING));
        return Map.copyOf(result);
    }
}
