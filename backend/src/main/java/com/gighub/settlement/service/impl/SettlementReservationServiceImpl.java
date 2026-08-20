package com.gighub.settlement.service.impl;

import java.time.LocalDateTime;

import com.gighub.settlement.domain.SettlementCalculation;
import com.gighub.settlement.domain.SettlementCalculationPolicy;
import com.gighub.settlement.domain.SettlementCalculationReason;
import com.gighub.settlement.mapper.SettlementMapper;
import com.gighub.settlement.service.SettlementReservationService;
import com.gighub.settlement.service.command.SettlementCalculationCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Settlement owner Mapper와 영향 행 검증을 공개 의미 명령 뒤에 둡니다. */
@Service
@RequiredArgsConstructor
public class SettlementReservationServiceImpl implements SettlementReservationService {

    private final SettlementMapper settlementMapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveWaiting(long workCaseId, long amount) {
        if (settlementMapper.insertWaiting(workCaseId, amount) != 1) {
            throw new IllegalStateException("정산 예약을 생성하지 못했습니다.");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void schedulePayout(
            SettlementCalculationCommand command, LocalDateTime dueAt) {
        requireCommonCommand(command);
        if (command.getReason() != SettlementCalculationReason.CHECKED_OUT
                || command.getCheckedOutAt() == null
                || dueAt == null) {
            throw new IllegalArgumentException("퇴근 정산 Snapshot 명령이 올바르지 않습니다.");
        }
        SettlementCalculation calculation = SettlementCalculationPolicy.checkedOut(
                command.getAgreedWage(),
                command.getStartsAt(),
                command.getEndsAt(),
                command.getBreakMinutes(),
                command.getBreakPaid(),
                command.getCheckedInAt(),
                command.getCheckedOutAt());
        // 같은 요청의 재시도는 멱등 Claim이 Replay로 흡수해 이 Method를 다시 부르지
        // 않습니다. 그래서 0행은 "이미 예약됨"이 아니라 Settlement가 없거나 WAITING이
        // 아닌 이상 상태이며, 근태·상태 전이만 commit되고 지급 예약이 빠지면 안 되므로
        // 전체를 되돌립니다.
        if (settlementMapper.scheduleWaitingPayout(
                command.getWorkCaseId(), dueAt, calculation) != 1) {
            throw new IllegalStateException("정산 지급 예약을 반영하지 못했습니다.");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordTerminalSnapshot(SettlementCalculationCommand command) {
        requireCommonCommand(command);
        if (command.getReason() != SettlementCalculationReason.NO_SHOW
                && command.getReason() != SettlementCalculationReason.CHECK_OUT_MISSING) {
            throw new IllegalArgumentException("종료 정산 Snapshot 사유가 올바르지 않습니다.");
        }
        SettlementCalculation calculation = SettlementCalculationPolicy.fullRefund(
                command.getAgreedWage(),
                command.getStartsAt(),
                command.getEndsAt(),
                command.getBreakMinutes(),
                command.getBreakPaid(),
                command.getCheckedInAt(),
                command.getReason());
        if (settlementMapper.recordWaitingTerminalSnapshot(
                command.getWorkCaseId(), calculation) != 1) {
            throw new IllegalStateException("종료 정산 Snapshot을 기록하지 못했습니다.");
        }
    }

    private static void requireCommonCommand(SettlementCalculationCommand command) {
        if (command == null
                || command.getWorkCaseId() == null
                || command.getWorkCaseId() <= 0
                || command.getAgreedWage() == null
                || command.getAgreedWage() <= 0
                || command.getBreakMinutes() == null
                || command.getBreakPaid() == null
                || command.getReason() == null) {
            throw new IllegalArgumentException("정산 Snapshot 명령이 올바르지 않습니다.");
        }
    }
}
