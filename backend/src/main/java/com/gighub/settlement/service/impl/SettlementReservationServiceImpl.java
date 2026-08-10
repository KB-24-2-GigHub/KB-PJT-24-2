package com.gighub.settlement.service.impl;

import com.gighub.settlement.mapper.SettlementMapper;
import com.gighub.settlement.service.SettlementReservationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Settlement owner Mapper와 영향 행 검증을 공개 의미 명령 뒤에 둡니다. */
@Service
public class SettlementReservationServiceImpl implements SettlementReservationService {

    private final SettlementMapper settlementMapper;

    public SettlementReservationServiceImpl(SettlementMapper settlementMapper) {
        this.settlementMapper = settlementMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveWaiting(long workCaseId, long amount) {
        if (settlementMapper.insertWaiting(workCaseId, amount) != 1) {
            throw new IllegalStateException("정산 예약을 생성하지 못했습니다.");
        }
    }
}
