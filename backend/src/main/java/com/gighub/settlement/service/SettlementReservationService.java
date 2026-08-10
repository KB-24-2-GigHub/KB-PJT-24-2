package com.gighub.settlement.service;

/** 수락 outer Transaction에 참여해 WAITING Settlement를 예약합니다. */
public interface SettlementReservationService {

    void reserveWaiting(long workCaseId, long amount);
}
