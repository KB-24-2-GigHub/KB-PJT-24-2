package com.gighub.settlement.service;

import com.gighub.settlement.service.command.SettlementPayoutCommand;
import com.gighub.settlement.service.policy.SettlementPayoutDecision;
import com.gighub.settlement.service.policy.SettlementPayoutRejectedException;
import com.gighub.settlement.service.policy.SettlementRetryDecision;
import com.gighub.settlement.service.policy.SettlementRetryPolicy;
import com.gighub.settlement.mapper.SettlementMapper;
import com.gighub.settlement.service.result.SettlementResult;
import com.gighub.work.service.WorkSettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * #172 Scheduler가 후보 하나를 처리하는 두 개의 독립된 짧은 Transaction을 제공합니다.
 *
 * <p>SETTLE-003은 지급 시도와 실패 감사 기록이 서로 다른 Transaction이어야 한다고 정합니다.
 * 지급이 Rollback되어도 {@code retry_count}·{@code failure_code} 같은 감사 정보는 남아야
 * 하기 때문입니다. 이 두 메서드는 반드시 이 Bean의 Proxy를 통해서만 호출해야 {@code @Transactional}
 * 경계가 실제로 적용됩니다 — 같은 Bean 안에서 한 메서드가 {@code this.other(...)}로 다른
 * 메서드를 직접 부르면 Proxy를 우회해 Transaction이 걸리지 않습니다. 그래서 호출 순서를 잇는
 * 역할은 이 클래스가 아니라 후보를 순회하는 별도 Scheduler가 맡습니다.</p>
 */
@Service
@RequiredArgsConstructor
public class SettlementScheduledPayoutService {

    private final SettlementMapper settlementMapper;
    private final SettlementPayoutExecutor payoutExecutor;
    private final WorkSettlementService workSettlementService;

    /**
     * work_cases를 SKIP LOCKED로 먼저 잠근 뒤 같은 Transaction에서 바로 지급을 실행한다.
     *
     * <p>{@code SettlementPayoutExecutor}가 수동 승인과 공유하는 Work → Settlement 고정 잠금
     * 순서를 Scheduler도 그대로 지키도록, 배치 조회가 이미 알려준 {@code workCaseId}로 Work
     * 행을 먼저 선점한다. 이 선점에 실패하면(수동 승인이나 다른 Scheduler 인스턴스가 이미 이
     * Work Case를 잠그고 있으면) 대기하지 않고 건너뛴다. due_at·next_retry_at·분쟁 같은 상세
     * 자격은 이 뒤에 실행기가 {@code SettlementPayoutPolicy}로 같은 Transaction에서 다시
     * 검증한다.</p>
     *
     * <p>실패하면 이 Transaction 전체가 Rollback되어 행은 {@code SCHEDULED}로 남는다. 호출자는
     * 던져진 예외를 잡아 {@link #recordFailure}로 넘겨야 한다.</p>
     *
     * @return 지급 결과, 다른 실행 주체가 이미 선점했거나 자격을 잃었으면 {@code null}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SettlementResult attemptPayout(
            long settlementId, long workCaseId, LocalDateTime eligibilityTime) {
        if (!workSettlementService.tryLockEscrowContext(workCaseId)) {
            return null;
        }
        return payoutExecutor.execute(SettlementPayoutCommand.scheduled(
                settlementId, workCaseId, eligibilityTime));
    }

    /**
     * {@link #attemptPayout}이 던진 예외를 별도 짧은 감사 Transaction에서 기록한다.
     *
     * <p>{@code ON_HOLD}·{@code NOT_READY}·{@code ALREADY_PROCESSED} 거절은 실패가 아니라
     * 다른 실행 주체가 먼저 처리했거나 더 이상 대상이 아니라는 정상적인 결과이므로 기록 없이
     * 건너뛴다. 그 사이 다른 실행 주체가 이미 상태를 옮겨 더 이상 {@code SCHEDULED}가 아니게
     * 된 경우도 같게 다룬다.</p>
     *
     * @return 재시도 또는 최종 실패로 기록했으면 {@code true}, 기록 없이 건너뛰었으면 {@code false}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailure(long settlementId, RuntimeException failure, LocalDateTime now) {
        if (isBenignRejection(failure)) {
            return false;
        }
        Integer currentRetryCount = settlementMapper.findRetryCountForUpdate(settlementId);
        if (currentRetryCount == null) {
            return false;
        }
        SettlementRetryDecision decision =
                SettlementRetryPolicy.decide(currentRetryCount, failure, now);
        int changed = decision.isRetryable()
                ? settlementMapper.recordScheduledPayoutRetry(
                        settlementId, decision.getFailureCode(), decision.getNextRetryAt())
                : settlementMapper.recordScheduledPayoutFailed(
                        settlementId, decision.getFailureCode());
        return changed == 1;
    }

    private boolean isBenignRejection(RuntimeException failure) {
        return failure instanceof SettlementPayoutRejectedException rejected
                && rejected.getDecision() != SettlementPayoutDecision.INTEGRITY_VIOLATION;
    }
}
