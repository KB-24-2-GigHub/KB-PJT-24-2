package com.gighub.settlement.service.policy;

import com.gighub.settlement.domain.SettlementStatus;
import com.gighub.wallet.domain.EscrowStatus;
import com.gighub.wallet.service.result.SettlementEscrowSnapshot;
import com.gighub.work.contract.WorkCaseEscrowSnapshot;
import com.gighub.work.domain.WorkCaseStatus;

import java.time.LocalDateTime;

/** NO_SHOW 환불 승인에 필요한 Work·Settlement·Escrow 조건을 판정합니다. */
public class NoShowRefundPolicy {

    public SettlementPayoutDecision assessWork(
            Long workCaseId, Long ownerUserId, WorkCaseEscrowSnapshot work) {
        if (workCaseId == null || workCaseId <= 0 || ownerUserId == null || ownerUserId <= 0) {
            return SettlementPayoutDecision.INTEGRITY_VIOLATION;
        }
        if (work == null
                || work.getWorkCaseId() == null
                || !workCaseId.equals(work.getWorkCaseId())) {
            return SettlementPayoutDecision.RESOURCE_NOT_FOUND;
        }
        if (!ownerUserId.equals(work.getEmployerId())) {
            // 다른 OWNER에게 근무 건의 존재 여부를 노출하지 않습니다.
            return SettlementPayoutDecision.RESOURCE_NOT_FOUND;
        }
        if (work.getEmployerId() <= 0
                || work.getWorkerId() == null
                || work.getWorkerId() <= 0
                || work.getEmployerId().equals(work.getWorkerId())
                || work.getAgreedWage() == null
                || work.getAgreedWage() <= 0
                || work.getStatus() == null
                || work.getSuccessfulCheckInCount() == null
                || work.getSuccessfulCheckInCount() < 0) {
            return SettlementPayoutDecision.INTEGRITY_VIOLATION;
        }
        if (work.getStatus() != WorkCaseStatus.NO_SHOW
                || work.getSuccessfulCheckInCount() != 0L) {
            return SettlementPayoutDecision.NOT_READY;
        }
        return SettlementPayoutDecision.ALLOWED;
    }

    public SettlementPayoutDecision assessSettlement(
            Long workCaseId,
            Long ownerUserId,
            WorkCaseEscrowSnapshot work,
            RefundSettlementFacts settlement) {
        SettlementPayoutDecision workDecision = assessWork(workCaseId, ownerUserId, work);
        if (workDecision != SettlementPayoutDecision.ALLOWED) {
            return workDecision;
        }
        if (settlement == null
                || settlement.settlementId() == null
                || settlement.settlementId() <= 0
                || settlement.workCaseId() == null
                || settlement.amount() == null
                || settlement.amount() <= 0
                || settlement.status() == null) {
            return SettlementPayoutDecision.INTEGRITY_VIOLATION;
        }
        if (!workCaseId.equals(settlement.workCaseId())
                || !work.getAgreedWage().equals(settlement.amount())) {
            return SettlementPayoutDecision.INTEGRITY_VIOLATION;
        }
        if (settlement.status() == SettlementStatus.ON_HOLD) {
            return SettlementPayoutDecision.ON_HOLD;
        }
        if (settlement.status() == SettlementStatus.COMPLETED
                || settlement.status() == SettlementStatus.REFUNDED) {
            return SettlementPayoutDecision.ALREADY_PROCESSED;
        }
        if (settlement.status() != SettlementStatus.WAITING) {
            return SettlementPayoutDecision.NOT_READY;
        }
        return settlement.dueAt() == null
                ? SettlementPayoutDecision.ALLOWED
                : SettlementPayoutDecision.INTEGRITY_VIOLATION;
    }

    public SettlementPayoutDecision assessRefund(
            Long workCaseId,
            Long ownerUserId,
            WorkCaseEscrowSnapshot work,
            RefundSettlementFacts settlement,
            SettlementEscrowSnapshot escrow,
            boolean hasBlockingDispute) {
        SettlementPayoutDecision settlementDecision =
                assessSettlement(workCaseId, ownerUserId, work, settlement);
        if (settlementDecision != SettlementPayoutDecision.ALLOWED) {
            return settlementDecision;
        }
        if (escrow == null) {
            return SettlementPayoutDecision.NOT_READY;
        }
        if (escrow.getStatus() == null
                || escrow.getEscrowId() == null
                || escrow.getEscrowId() <= 0
                || escrow.getWorkCaseId() == null
                || escrow.getAmount() == null
                || escrow.getAmount() <= 0) {
            return SettlementPayoutDecision.INTEGRITY_VIOLATION;
        }
        if (!workCaseId.equals(escrow.getWorkCaseId())
                || !work.getAgreedWage().equals(escrow.getAmount())
                || !settlement.amount().equals(escrow.getAmount())) {
            return SettlementPayoutDecision.INTEGRITY_VIOLATION;
        }
        if (escrow.getStatus() != EscrowStatus.HELD) {
            return SettlementPayoutDecision.NOT_READY;
        }
        return hasBlockingDispute
                ? SettlementPayoutDecision.ON_HOLD
                : SettlementPayoutDecision.ALLOWED;
    }

    public record RefundSettlementFacts(
            Long settlementId,
            Long workCaseId,
            Long amount,
            SettlementStatus status,
            LocalDateTime dueAt) {
    }
}
