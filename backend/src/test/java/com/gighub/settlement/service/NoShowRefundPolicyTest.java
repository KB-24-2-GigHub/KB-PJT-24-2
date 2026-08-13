package com.gighub.settlement.service;

import com.gighub.settlement.domain.SettlementStatus;
import com.gighub.settlement.service.policy.NoShowRefundPolicy;
import com.gighub.settlement.service.policy.NoShowRefundPolicy.RefundSettlementFacts;
import com.gighub.settlement.service.policy.SettlementPayoutDecision;
import com.gighub.wallet.domain.EscrowStatus;
import com.gighub.wallet.service.result.SettlementEscrowSnapshot;
import com.gighub.work.contract.WorkCaseEscrowSnapshot;
import com.gighub.work.domain.WorkCaseStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NoShowRefundPolicyTest {

    private static final long WORK_CASE_ID = 1L;
    private static final long OWNER_ID = 3L;
    private static final long WORKER_ID = 4L;
    private static final long WAGE = 300_000L;

    private final NoShowRefundPolicy policy = new NoShowRefundPolicy();

    @Test
    void allowsOnlyNoShowWithoutASuccessfulCheckIn() {
        assertEquals(
                SettlementPayoutDecision.ALLOWED,
                policy.assessRefund(
                        WORK_CASE_ID,
                        OWNER_ID,
                        work(WorkCaseStatus.NO_SHOW, 0L),
                        settlement(SettlementStatus.WAITING),
                        escrow(EscrowStatus.HELD),
                        false));
        assertEquals(
                SettlementPayoutDecision.NOT_READY,
                policy.assessWork(
                        WORK_CASE_ID,
                        OWNER_ID,
                        work(WorkCaseStatus.NO_SHOW, 1L)));
        assertEquals(
                SettlementPayoutDecision.NOT_READY,
                policy.assessWork(
                        WORK_CASE_ID,
                        OWNER_ID,
                        work(WorkCaseStatus.READY, 0L)));
    }

    @Test
    void hidesAnotherOwnerAndRejectsAnOpenDispute() {
        assertEquals(
                SettlementPayoutDecision.RESOURCE_NOT_FOUND,
                policy.assessWork(
                        WORK_CASE_ID,
                        99L,
                        work(WorkCaseStatus.NO_SHOW, 0L)));
        assertEquals(
                SettlementPayoutDecision.ON_HOLD,
                policy.assessRefund(
                        WORK_CASE_ID,
                        OWNER_ID,
                        work(WorkCaseStatus.NO_SHOW, 0L),
                        settlement(SettlementStatus.WAITING),
                        escrow(EscrowStatus.HELD),
                        true));
    }

    @Test
    void mapsTerminalSettlementsToAlreadyProcessed() {
        assertEquals(
                SettlementPayoutDecision.ALREADY_PROCESSED,
                policy.assessSettlement(
                        WORK_CASE_ID,
                        OWNER_ID,
                        work(WorkCaseStatus.NO_SHOW, 0L),
                        settlement(SettlementStatus.COMPLETED)));
        assertEquals(
                SettlementPayoutDecision.ALREADY_PROCESSED,
                policy.assessSettlement(
                        WORK_CASE_ID,
                        OWNER_ID,
                        work(WorkCaseStatus.NO_SHOW, 0L),
                        settlement(SettlementStatus.REFUNDED)));
    }

    @ParameterizedTest
    @EnumSource(value = EscrowStatus.class, names = "HELD", mode = EnumSource.Mode.EXCLUDE)
    void onlyHeldEscrowCanBeRefunded(EscrowStatus status) {
        assertEquals(
                SettlementPayoutDecision.NOT_READY,
                policy.assessRefund(
                        WORK_CASE_ID,
                        OWNER_ID,
                        work(WorkCaseStatus.NO_SHOW, 0L),
                        settlement(SettlementStatus.WAITING),
                        escrow(status),
                        false));
    }

    @Test
    void amountMismatchFailsClosed() {
        assertEquals(
                SettlementPayoutDecision.INTEGRITY_VIOLATION,
                policy.assessRefund(
                        WORK_CASE_ID,
                        OWNER_ID,
                        work(WorkCaseStatus.NO_SHOW, 0L),
                        settlement(SettlementStatus.WAITING),
                        escrow(EscrowStatus.HELD).toBuilder().amount(WAGE - 1).build(),
                        false));
    }

    private WorkCaseEscrowSnapshot work(WorkCaseStatus status, long checkInCount) {
        return WorkCaseEscrowSnapshot.builder()
                .workCaseId(WORK_CASE_ID)
                .employerId(OWNER_ID)
                .workerId(WORKER_ID)
                .agreedWage(WAGE)
                .status(status)
                .successfulCheckInCount(checkInCount)
                .build();
    }

    private RefundSettlementFacts settlement(SettlementStatus status) {
        return new RefundSettlementFacts(12L, WORK_CASE_ID, WAGE, status, null);
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
