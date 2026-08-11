package com.gighub.settlement.service.impl;

import java.time.LocalDateTime;

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

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void scheduleDueAt(long workCaseId, LocalDateTime dueAt) {
        // 재시도는 0행으로 조용히 멱등 처리하므로 영향 행 수를 검증하지 않습니다.
        settlementMapper.scheduleDueAtWaiting(workCaseId, dueAt);
    }
}
