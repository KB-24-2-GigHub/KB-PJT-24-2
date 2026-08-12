package com.gighub.settlement.service;

import com.gighub.settlement.domain.SettlementStatus;
import com.gighub.settlement.service.command.SettlementPayoutCommand;
import com.gighub.settlement.service.policy.SettlementPayoutDecision;
import com.gighub.settlement.service.policy.SettlementPayoutPolicy;
import com.gighub.settlement.service.policy.SettlementPayoutPolicy.SettlementFacts;
import com.gighub.wallet.domain.EscrowStatus;
import com.gighub.wallet.service.result.SettlementEscrowSnapshot;
import com.gighub.work.contract.WorkCaseEscrowSnapshot;
import com.gighub.work.domain.WorkCaseStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettlementPayoutPolicyTest {

    private static final long WORK_CASE_ID = 1L;
    private static final long OWNER_ID = 3L;
    private static final long WORKER_ID = 4L;
    private static final long WAGE = 300_000L;
    private static final LocalDateTime DUE_AT = LocalDateTime.of(2026, 8, 13, 10, 0);

    private final SettlementPayoutPolicy policy = new SettlementPayoutPolicy();

    @ParameterizedTest
    @EnumSource(value = WorkCaseStatus.class, names = "COMPLETED", mode = EnumSource.Mode.EXCLUDE)
    void onlyCompletedWorkCanBePaid(WorkCaseStatus status) {
        assertEquals(
                SettlementPayoutDecision.NOT_READY,
                policy.assessWork(ownerCommand(), work(status)));
    }

    @ParameterizedTest
    @EnumSource(value = SettlementStatus.class, names = {
            "SCHEDULED", "ON_HOLD", "COMPLETED", "REFUNDED"
    }, mode = EnumSource.Mode.EXCLUDE)
    void waitingProcessingAndFailedAreNotOwnerApprovable(SettlementStatus status) {
        assertEquals(
                SettlementPayoutDecision.NOT_READY,
                policy.assessSettlement(ownerCommand(), work(WorkCaseStatus.COMPLETED),
                        settlement(status)));
    }

    @ParameterizedTest
    @EnumSource(value = EscrowStatus.class, names = "HELD", mode = EnumSource.Mode.EXCLUDE)
    void onlyHeldEscrowCanBePaid(EscrowStatus status) {
        assertEquals(
                SettlementPayoutDecision.NOT_READY,
                policy.assessPayout(
                        ownerCommand(),
                        work(WorkCaseStatus.COMPLETED),
                        settlement(SettlementStatus.SCHEDULED),
                        escrow(status),
                        false));
    }

    @Test
    void mapsHoldAndTerminalStatesToStableConflictMeanings() {
        assertEquals(
                SettlementPayoutDecision.ON_HOLD,
                policy.assessSettlement(ownerCommand(), work(WorkCaseStatus.COMPLETED),
                        settlement(SettlementStatus.ON_HOLD)));
        assertEquals(
                SettlementPayoutDecision.ALREADY_PROCESSED,
                policy.assessSettlement(ownerCommand(), work(WorkCaseStatus.COMPLETED),
                        settlement(SettlementStatus.COMPLETED)));
        assertEquals(
                SettlementPayoutDecision.ALREADY_PROCESSED,
                policy.assessSettlement(ownerCommand(), work(WorkCaseStatus.COMPLETED),
                        settlement(SettlementStatus.REFUNDED)));
    }

    @Test
    void anotherOwnerIsHiddenAndAmountMismatchesFailClosed() {
        assertEquals(
                SettlementPayoutDecision.RESOURCE_NOT_FOUND,
                policy.assessWork(
                        SettlementPayoutCommand.ownerApproval(WORK_CASE_ID, 99L),
                        work(WorkCaseStatus.COMPLETED)));
        assertEquals(
                SettlementPayoutDecision.INTEGRITY_VIOLATION,
                policy.assessPayout(
                        ownerCommand(),
                        work(WorkCaseStatus.COMPLETED),
                        settlement(SettlementStatus.SCHEDULED),
                        escrow(EscrowStatus.HELD).toBuilder().amount(WAGE - 1).build(),
                        false));
    }

    @Test
    void schedulerUsesTheSamePolicyButMustReachDueAt() {
        SettlementPayoutCommand before = SettlementPayoutCommand.scheduled(
                12L, WORK_CASE_ID, DUE_AT.minusNanos(1));
        SettlementPayoutCommand exact = SettlementPayoutCommand.scheduled(
                12L, WORK_CASE_ID, DUE_AT);
        SettlementPayoutCommand after = SettlementPayoutCommand.scheduled(
                12L, WORK_CASE_ID, DUE_AT.plusNanos(1));

        assertEquals(
                SettlementPayoutDecision.NOT_READY,
                policy.assessSettlement(
                        before, work(WorkCaseStatus.COMPLETED),
                        settlement(SettlementStatus.SCHEDULED)));
        assertEquals(
                SettlementPayoutDecision.ALLOWED,
                policy.assessSettlement(
                        exact, work(WorkCaseStatus.COMPLETED),
                        settlement(SettlementStatus.SCHEDULED)));
        assertEquals(
                SettlementPayoutDecision.ALLOWED,
                policy.assessSettlement(
                        after, work(WorkCaseStatus.COMPLETED),
                        settlement(SettlementStatus.SCHEDULED)));
    }

    @Test
    void schedulerHonorsTheLockedRetryBoundaryButOwnerDoesNotWaitForIt() {
        LocalDateTime retryAt = DUE_AT.plusMinutes(5);
        SettlementFacts waitingForRetry = settlement(SettlementStatus.SCHEDULED, retryAt);

        assertEquals(
                SettlementPayoutDecision.NOT_READY,
                policy.assessSettlement(
                        SettlementPayoutCommand.scheduled(
                                12L, WORK_CASE_ID, retryAt.minusNanos(1)),
                        work(WorkCaseStatus.COMPLETED),
                        waitingForRetry));
        assertEquals(
                SettlementPayoutDecision.ALLOWED,
                policy.assessSettlement(
                        SettlementPayoutCommand.scheduled(
                                12L, WORK_CASE_ID, retryAt),
                        work(WorkCaseStatus.COMPLETED),
                        waitingForRetry));
        assertEquals(
                SettlementPayoutDecision.ALLOWED,
                policy.assessSettlement(
                        ownerCommand(),
                        work(WorkCaseStatus.COMPLETED),
                        waitingForRetry));
    }

    @Test
    void anOpenDisputeBlocksBothManualAndScheduledPayout() {
        assertEquals(
                SettlementPayoutDecision.ON_HOLD,
                policy.assessPayout(
                        ownerCommand(),
                        work(WorkCaseStatus.COMPLETED),
                        settlement(SettlementStatus.SCHEDULED),
                        escrow(EscrowStatus.HELD),
                        true));
        assertEquals(
                SettlementPayoutDecision.ON_HOLD,
                policy.assessPayout(
                        SettlementPayoutCommand.scheduled(
                                12L, WORK_CASE_ID, DUE_AT),
                        work(WorkCaseStatus.COMPLETED),
                        settlement(SettlementStatus.SCHEDULED),
                        escrow(EscrowStatus.HELD),
                        true));
    }

    private SettlementPayoutCommand ownerCommand() {
        return SettlementPayoutCommand.ownerApproval(WORK_CASE_ID, OWNER_ID);
    }

    private WorkCaseEscrowSnapshot work(WorkCaseStatus status) {
        return WorkCaseEscrowSnapshot.builder()
                .workCaseId(WORK_CASE_ID)
                .employerId(OWNER_ID)
                .workerId(WORKER_ID)
                .agreedWage(WAGE)
                .status(status)
                .build();
    }

    private SettlementFacts settlement(SettlementStatus status) {
        return settlement(status, null);
    }

    private SettlementFacts settlement(
            SettlementStatus status, LocalDateTime nextRetryAt) {
        return new SettlementFacts(
                12L, WORK_CASE_ID, WAGE, status, DUE_AT, nextRetryAt);
    }

    private SettlementEscrowSnapshot escrow(EscrowStatus status) {
        return SettlementEscrowSnapshot.builder()
                .escrowId(19L)
                .workCaseId(WORK_CASE_ID)
                .amount(WAGE)
                .status(status)
                .build();
    }
}
