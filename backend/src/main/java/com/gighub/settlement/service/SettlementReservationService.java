package com.gighub.settlement.service;

import java.time.LocalDateTime;

/** 수락·근태 완료 outer Transaction이 참여하는 Settlement 공개 명령입니다. */
public interface SettlementReservationService {

    /** 수락 outer Transaction에 참여해 WAITING Settlement를 예약합니다. */
    void reserveWaiting(long workCaseId, long amount);

    /**
     * 근태 완료 outer Transaction에 참여해 지급 예정 시각을 예약합니다.
     *
     * <p>status와 금액은 바꾸지 않습니다. Wallet·Escrow 자금은 이 호출로 움직이지
     * 않습니다.</p>
     */
    void scheduleDueAt(long workCaseId, LocalDateTime dueAt);
}
