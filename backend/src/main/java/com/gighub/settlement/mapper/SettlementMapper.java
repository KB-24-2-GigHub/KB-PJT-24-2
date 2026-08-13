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
     * #172 Scheduler가 한 실행에서 훑을 후보 ID를 고른다.
     *
     * <p>잠그지 않는 배치 조회이므로 이 목록의 각 ID는 건별 짧은 Transaction에서
     * {@link #lockScheduledPayoutCandidate}로 다시 잠그고 자격을 재확인한 뒤에만 선점한다.</p>
     */
    List<Long> findScheduledPayoutCandidateIds(
            @Param("eligibilityTime") LocalDateTime eligibilityTime,
            @Param("limit") int limit);

    /**
     * 건별 짧은 Transaction 안에서 후보 행을 잠그고 자격을 다시 확인한다.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED}이므로 다른 Scheduler 인스턴스나 OWNER 승인이 같은
     * 행을 먼저 잠그고 있으면 대기하지 않고 즉시 {@code null}을 반환한다. 호출부는 {@code null}을
     * "이번 실행에서는 건너뛴다"로 처리해야 한다. 배치 조회는 ID만 주므로 지급 실행에 필요한
     * {@code workCaseId}는 이 잠금 조회가 함께 돌려준다.</p>
     *
     * @return 잠금에 성공한 후보, 이미 잠겼거나 자격을 잃었으면 {@code null}
     */
    ScheduledPayoutCandidate lockScheduledPayoutCandidate(
            @Param("settlementId") Long settlementId,
            @Param("eligibilityTime") LocalDateTime eligibilityTime);

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
}
