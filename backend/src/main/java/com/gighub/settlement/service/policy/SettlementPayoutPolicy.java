package com.gighub.settlement.service.policy;

import com.gighub.settlement.domain.SettlementPayoutTrigger;
import com.gighub.settlement.domain.SettlementStatus;
import com.gighub.settlement.service.command.SettlementPayoutCommand;
import com.gighub.wallet.domain.EscrowStatus;
import com.gighub.wallet.service.result.SettlementEscrowSnapshot;
import com.gighub.work.contract.WorkCaseEscrowSnapshot;
import com.gighub.work.domain.WorkCaseStatus;

import java.time.LocalDateTime;

/** Work·Settlement·Escrow Snapshot을 변경하지 않고 지급 가능 여부만 판단합니다. */
public class SettlementPayoutPolicy {

    public SettlementPayoutDecision assessWork(
            SettlementPayoutCommand command, WorkCaseEscrowSnapshot work) {
        if (command == null
                || command.getWorkCaseId() == null
                || command.getWorkCaseId() <= 0
                || command.getTrigger() == null) {
            return SettlementPayoutDecision.INTEGRITY_VIOLATION;
        }
        if (work == null
                || work.getWorkCaseId() == null
                || !work.getWorkCaseId().equals(command.getWorkCaseId())) {
            return SettlementPayoutDecision.RESOURCE_NOT_FOUND;
        }
        if (command.getTrigger() == SettlementPayoutTrigger.OWNER_APPROVAL
                && (command.getActorUserId() == null
                || !command.getActorUserId().equals(work.getEmployerId()))) {
            // 다른 OWNER에게 근무 건의 존재 여부를 알려주지 않습니다.
            return SettlementPayoutDecision.RESOURCE_NOT_FOUND;
        }
        if (command.getTrigger() == SettlementPayoutTrigger.SCHEDULER
                && (command.getSettlementId() == null
                || command.getSettlementId() <= 0
                || command.getActorUserId() != null
                || command.getEligibilityTime() == null)) {
            return SettlementPayoutDecision.INTEGRITY_VIOLATION;
        }
        if (work.getEmployerId() == null
                || work.getEmployerId() <= 0
                || work.getWorkerId() == null
                || work.getWorkerId() <= 0
                || work.getEmployerId().equals(work.getWorkerId())
                || work.getAgreedWage() == null
                || work.getAgreedWage() <= 0
                || work.getStatus() == null) {
            return SettlementPayoutDecision.INTEGRITY_VIOLATION;
        }
        return work.getStatus() == WorkCaseStatus.COMPLETED
                ? SettlementPayoutDecision.ALLOWED
                : SettlementPayoutDecision.NOT_READY;
    }

    public SettlementPayoutDecision assessSettlement(
            SettlementPayoutCommand command,
            WorkCaseEscrowSnapshot work,
            SettlementFacts settlement) {
        SettlementPayoutDecision workDecision = assessWork(command, work);
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
        if (!work.getWorkCaseId().equals(settlement.workCaseId())
                || !work.getAgreedWage().equals(settlement.amount())) {
            return SettlementPayoutDecision.INTEGRITY_VIOLATION;
        }
        if (command.getTrigger() == SettlementPayoutTrigger.SCHEDULER
                && !command.getSettlementId().equals(settlement.settlementId())) {
            return SettlementPayoutDecision.INTEGRITY_VIOLATION;
        }
        if (settlement.status() == SettlementStatus.ON_HOLD) {
            return SettlementPayoutDecision.ON_HOLD;
        }
        if (settlement.status() == SettlementStatus.COMPLETED
                || settlement.status() == SettlementStatus.REFUNDED) {
            return SettlementPayoutDecision.ALREADY_PROCESSED;
        }
        if (settlement.status() != SettlementStatus.SCHEDULED) {
            return SettlementPayoutDecision.NOT_READY;
        }
        if (settlement.dueAt() == null) {
            return SettlementPayoutDecision.INTEGRITY_VIOLATION;
        }
        if (command.getTrigger() == SettlementPayoutTrigger.SCHEDULER
                && settlement.dueAt().isAfter(command.getEligibilityTime())) {
            return SettlementPayoutDecision.NOT_READY;
        }
        if (command.getTrigger() == SettlementPayoutTrigger.SCHEDULER
                && settlement.nextRetryAt() != null
                && settlement.nextRetryAt().isAfter(command.getEligibilityTime())) {
            return SettlementPayoutDecision.NOT_READY;
        }
        return SettlementPayoutDecision.ALLOWED;
    }

    public SettlementPayoutDecision assessPayout(
            SettlementPayoutCommand command,
            WorkCaseEscrowSnapshot work,
            SettlementFacts settlement,
            SettlementEscrowSnapshot escrow,
            boolean hasBlockingDispute) {
        SettlementPayoutDecision settlementDecision =
                assessSettlement(command, work, settlement);
        if (settlementDecision != SettlementPayoutDecision.ALLOWED) {
            return settlementDecision;
        }
        if (escrow == null) {
            return SettlementPayoutDecision.NOT_READY;
        }
        if (escrow.getStatus() == null) {
            return SettlementPayoutDecision.INTEGRITY_VIOLATION;
        }
        if (escrow.getEscrowId() == null
                || escrow.getEscrowId() <= 0
                || escrow.getWorkCaseId() == null
                || escrow.getAmount() == null
                || escrow.getAmount() <= 0) {
            return SettlementPayoutDecision.INTEGRITY_VIOLATION;
        }
        if (!work.getWorkCaseId().equals(escrow.getWorkCaseId())
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

    /** Mapper Snapshot에서 정책에 필요한 필드만 옮긴 값입니다. */
    public record SettlementFacts(
            Long settlementId,
            Long workCaseId,
            Long amount,
            SettlementStatus status,
            LocalDateTime dueAt,
            LocalDateTime nextRetryAt) {
    }
}
