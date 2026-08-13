package com.gighub.settlement.mapper;

import com.gighub.settlement.dto.ScheduledPayoutCandidate;
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
     * 시각을 채운다. 금액은 바꾸지 않고 자금도 움직이지 않는다. 같은 요청의 재시도는 멱등
     * Claim이 Replay로 흡수하므로 이 문장을 다시 부르지 않는다. 정상 경로에서는 항상
     * 정확히 1행이 바뀌어야 하며, 0행은 호출부가 이상 상태로 처리한다.</p>
     *
     * @return 변경된 행 수
     */
    int scheduleWaitingPayout(
            @Param("workCaseId") Long workCaseId,
            @Param("dueAt") LocalDateTime dueAt);

    // 상태 전이: 수동 승인 가능한 SCHEDULED 정산만 처리 중으로 바꾼다.
    int transitionScheduledToProcessing(
            @Param("settlementId") Long settlementId,
            @Param("approvedByUserId") Long approvedByUserId);

    // 상태 전이: Scheduler가 잠근 행의 due/retry 자격을 같은 DB 시각으로 다시 확인한다.
    int transitionEligibleScheduledToProcessing(
            @Param("settlementId") Long settlementId,
            @Param("eligibilityTime") LocalDateTime eligibilityTime);

    // 상태 전이: 같은 승인자가 처리 중인 정산만 완료한다.
    int transitionProcessingToCompleted(
            @Param("settlementId") Long settlementId,
            @Param("approvedByUserId") Long approvedByUserId);

    /**
     * #172 Scheduler가 한 실행에서 훑을 후보를 고른다.
     *
     * <p>잠그지 않는 배치 조회다. 각 후보의 실제 선점은 {@code WorkSettlementService}가
     * work_cases를 SKIP LOCKED로 먼저 잠근 뒤 {@code SettlementPayoutExecutor}가 같은
     * Transaction에서 이 목록의 due_at·next_retry_at·분쟁 조건을 다시 검증한다. Work → Settlement
     * 고정 잠금 순서를 지키려고 이 배치 조회 자체는 아무 것도 잠그지 않는다.</p>
     */
    List<ScheduledPayoutCandidate> findScheduledPayoutCandidates(
            @Param("eligibilityTime") LocalDateTime eligibilityTime,
            @Param("limit") int limit);

    /** Scheduler 자격 판정에 쓸 DB 자체 시각이다. 앱 서버 시계와 DB 시계가 어긋나도 이 값을 쓴다. */
    LocalDateTime currentDatabaseTime();

    /**
     * 실패 감사 기록 직전, 같은 Transaction에서 현재 실패 누적 횟수를 잠그고 읽는다.
     *
     * <p>{@code SCHEDULED}가 아니면(예: 그 사이 다른 실행 주체가 이미 완료했으면) 기록할
     * 대상이 없다는 뜻이므로 {@code null}을 돌려주고, 호출부는 감사 기록을 건너뛴다.</p>
     */
    Integer findRetryCountForUpdate(@Param("settlementId") Long settlementId);

    /**
     * 일시 실패를 감사 기록하고 {@code SCHEDULED}로 남긴다.
     *
     * <p>자금 Transaction은 이미 Rollback되어 행이 {@code SCHEDULED}로 돌아온 뒤, 별도 짧은
     * 감사 Transaction에서만 부른다. {@code retry_count &lt;= 3}인 행만 바꿔 최종 값이 계약이
     * 허용하는 {@code SCHEDULED} 최대치인 4를 넘지 않게 한다. 5번째 실패는 이 메서드가 아니라
     * {@link #recordScheduledPayoutFailed}로 처리한다.</p>
     *
     * @return 변경된 행 수
     */
    int recordScheduledPayoutRetry(
            @Param("settlementId") Long settlementId,
            @Param("failureCode") String failureCode,
            @Param("nextRetryAt") LocalDateTime nextRetryAt);

    /**
     * 재시도를 소진한 일시 실패 또는 즉시 격리해야 하는 영구 실패를 {@code FAILED}로 확정한다.
     *
     * <p>{@code retry_count}는 상한 5를 넘지 않게 SQL에서 직접 clamp한다. 자동 재처리 대상에서
     * 빠지며 관리자 승인 복구만 다음 상태 전이가 될 수 있다.</p>
     *
     * @return 변경된 행 수
     */
    int recordScheduledPayoutFailed(
            @Param("settlementId") Long settlementId,
            @Param("failureCode") String failureCode);

    /** NO_SHOW의 WAITING 정산을 OWNER 환불 처리 중으로 선점합니다. */
    int transitionWaitingToRefundProcessing(
            @Param("settlementId") Long settlementId,
            @Param("approvedByUserId") Long approvedByUserId);

    /** 같은 OWNER가 선점한 환불만 최종 REFUNDED로 전이합니다. */
    int transitionRefundProcessingToRefunded(
            @Param("settlementId") Long settlementId,
            @Param("approvedByUserId") Long approvedByUserId);
}
