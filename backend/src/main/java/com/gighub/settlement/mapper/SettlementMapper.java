package com.gighub.settlement.mapper;

import com.gighub.settlement.dto.SettlementSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SettlementMapper {

    /**
     * 수락 시점에 정산을 예약한다.
     *
     * <p>금액은 근무의 일급과 같아야 한다. 복합 FK {@code fk_settlements_case_wage}가
     * {@code (work_case_id, amount)}를 {@code work_cases(id, agreed_wage)}와 대조하므로 다른
     * 금액으로는 예약할 수 없다.</p>
     *
     * <p>상태와 시각은 승인 계약이 정한 초기값으로 서버가 고정한다. {@code due_at}과 승인·처리·
     * 완료·실패 필드는 이후 정산 흐름이 채운다.</p>
     *
     * @return 저장된 행 수
     */
    int insertWaiting(
            @Param("workCaseId") Long workCaseId,
            @Param("amount") Long amount);

    // 정산 원장: 지급 판단부터 완료까지 동일 행을 잠근다.
    SettlementSnapshot findByWorkCaseIdForUpdate(
            @Param("workCaseId") Long workCaseId);

    // 분쟁 게이트: 지급을 막는 분쟁 행과 빈 구간을 정산 완료까지 잠근다.
    List<Long> findBlockingDisputeIdsForUpdate(
            @Param("workCaseId") Long workCaseId);

    /**
     * M5 퇴근 완료가 지급을 예약한다.
     *
     * <p>{@code WAITING}이면서 아직 예약되지 않은 행만 {@code SCHEDULED}로 옮기고 지급 예정
     * 시각을 채운다. 금액은 바꾸지 않고 자금도 움직이지 않는다. 이미 예약된 행은 0행이므로
     * 같은 근무의 재시도가 예정 시각을 덮어쓰지 않는다.</p>
     *
     * @return 변경된 행 수
     */
    int scheduleWaitingPayout(
            @Param("workCaseId") Long workCaseId,
            @Param("dueAt") LocalDateTime dueAt);

    // 상태 전이: 수동 승인 가능한 WAITING 정산만 처리 중으로 바꾼다.
    int transitionWaitingToProcessing(
            @Param("settlementId") Long settlementId,
            @Param("approvedByUserId") Long approvedByUserId);

    // 상태 전이: 같은 승인자가 처리 중인 정산만 완료한다.
    int transitionProcessingToCompleted(
            @Param("settlementId") Long settlementId,
            @Param("approvedByUserId") Long approvedByUserId);
}
