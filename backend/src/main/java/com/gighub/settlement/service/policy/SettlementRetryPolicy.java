package com.gighub.settlement.service.policy;

import com.gighub.wallet.exception.EscrowIntegrityException;
import org.springframework.dao.TransientDataAccessException;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * SETTLE-003이 정한 #172 Scheduler 실패·재시도 경계를 순수 판단으로 제공합니다.
 *
 * <p>Deadlock·Lock Timeout·일시 Adapter 실패는 재시도 대상이고, 상태·금액·원장 무결성 실패는
 * 재시도 없이 즉시 최종 실패입니다. 이 클래스는 DB나 Scheduler 실행을 모르며 호출자가 이미
 * Rollback을 끝낸 뒤 감사 기록에 쓸 값만 계산합니다.</p>
 */
public final class SettlementRetryPolicy {

    /** 총 시도 횟수 상한. 이 번째 시도가 실패하면 재시도 없이 FAILED로 확정한다. */
    public static final int MAX_ATTEMPTS = 5;

    /** 1번째~4번째 실패 뒤 대기 시간. 인덱스는 (이번 실패까지의 누적 시도 횟수 - 1)이다. */
    private static final Duration[] BACKOFF_BY_ATTEMPT = {
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            Duration.ofMinutes(60),
    };

    public static final String FAILURE_CODE_LOCK_CONTENTION = "SCHEDULER_LOCK_CONTENTION";
    public static final String FAILURE_CODE_INTEGRITY_VIOLATION = "SCHEDULER_INTEGRITY_VIOLATION";
    public static final String FAILURE_CODE_UNEXPECTED = "SCHEDULER_UNEXPECTED_FAILURE";

    private SettlementRetryPolicy() {
    }

    /**
     * @param currentRetryCount 이번 실패 전까지 DB에 누적돼 있던 실패 횟수(0~4)
     * @param failure           지급 시도 Transaction이 Rollback된 원인
     * @param now               Scheduler가 이번 실행에서 얻은 기준 시각
     */
    public static SettlementRetryDecision decide(
            int currentRetryCount, Throwable failure, LocalDateTime now) {
        String failureCode = classifyFailureCode(failure);
        int attemptNumber = currentRetryCount + 1;

        if (!isTransient(failure) || attemptNumber >= MAX_ATTEMPTS) {
            return SettlementRetryDecision.terminal(failureCode);
        }
        return SettlementRetryDecision.retry(failureCode, now.plus(BACKOFF_BY_ATTEMPT[attemptNumber - 1]));
    }

    private static boolean isTransient(Throwable failure) {
        return failure instanceof TransientDataAccessException;
    }

    private static String classifyFailureCode(Throwable failure) {
        if (failure instanceof TransientDataAccessException) {
            return FAILURE_CODE_LOCK_CONTENTION;
        }
        if (failure instanceof EscrowIntegrityException
                || isIntegrityViolationRejection(failure)) {
            return FAILURE_CODE_INTEGRITY_VIOLATION;
        }
        return FAILURE_CODE_UNEXPECTED;
    }

    private static boolean isIntegrityViolationRejection(Throwable failure) {
        return failure instanceof SettlementPayoutRejectedException rejected
                && rejected.getDecision() == SettlementPayoutDecision.INTEGRITY_VIOLATION;
    }
}
