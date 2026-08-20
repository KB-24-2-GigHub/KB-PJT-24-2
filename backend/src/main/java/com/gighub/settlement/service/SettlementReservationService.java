package com.gighub.settlement.service;

import java.time.LocalDateTime;

import com.gighub.settlement.service.command.SettlementCalculationCommand;

/** 수락·근태 완료 outer Transaction이 참여하는 Settlement 공개 명령입니다. */
public interface SettlementReservationService {

    /** 수락 outer Transaction에 참여해 WAITING Settlement를 예약합니다. */
    void reserveWaiting(long workCaseId, long amount);

    /**
     * 근태 완료 outer Transaction에 참여해 지급을 예약합니다.
     *
     * <p>{@code WAITING} 정산을 {@code SCHEDULED}로 옮기고 지급 예정 시각을 채웁니다. 금액은
     * 바꾸지 않으며 Wallet·Escrow 자금은 이 호출로 움직이지 않습니다. 실제 지급은 M6가 이
     * 예약을 소비해 처리합니다.</p>
     */
    void schedulePayout(SettlementCalculationCommand command, LocalDateTime dueAt);

    /** NO_SHOW 또는 CHECK_OUT_MISSING 전이 Transaction에서 전액 환불 Snapshot을 한 번 기록합니다. */
    void recordTerminalSnapshot(SettlementCalculationCommand command);
}
