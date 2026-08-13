package com.gighub.settlement.service;

import com.gighub.settlement.mapper.SettlementMapper;
import com.gighub.settlement.service.result.SettlementResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * SETTLE-003이 정한 예정 자동 지급을 DB 기준 시각으로 선점·실행합니다.
 *
 * <p>후보 조회는 잠그지 않는 배치 조회이고, 각 후보의 선점·지급·실패 기록은
 * {@link SettlementScheduledPayoutService}가 노출하는 두 개의 독립된 짧은 Transaction을
 * 통해서만 이뤄집니다. 한 후보의 실패가 다른 후보 처리를 막지 않도록 후보별로 예외를
 * 잡습니다.</p>
 */
@Component
public class SettlementScheduledPayoutScheduler {

    static final int BATCH_SIZE = 100;

    private static final Logger log =
            LoggerFactory.getLogger(SettlementScheduledPayoutScheduler.class);
    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Seoul");

    private final SettlementMapper settlementMapper;
    private final SettlementScheduledPayoutService payoutService;
    private final Clock clock;

    @Autowired
    public SettlementScheduledPayoutScheduler(
            SettlementMapper settlementMapper,
            SettlementScheduledPayoutService payoutService) {
        this(settlementMapper, payoutService, Clock.system(DATABASE_ZONE));
    }

    /** 경계 시각 테스트에서만 고정 Clock을 주입합니다. */
    SettlementScheduledPayoutScheduler(
            SettlementMapper settlementMapper,
            SettlementScheduledPayoutService payoutService,
            Clock clock) {
        this.settlementMapper = settlementMapper;
        this.payoutService = payoutService;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void runOnce() {
        LocalDateTime now = LocalDateTime.now(clock);

        List<Long> candidateIds;
        try {
            candidateIds = settlementMapper.findScheduledPayoutCandidateIds(now, BATCH_SIZE);
        } catch (RuntimeException failure) {
            // 후보 조회 자체가 실패해도 다음 실행 주기에서 다시 시도하면 되므로 예외를 삼킨다.
            log.warn("Settlement 예정 자동 지급 후보 조회에 실패했습니다.", failure);
            return;
        }
        processCandidates(candidateIds, now);
    }

    private void processCandidates(List<Long> candidateIds, LocalDateTime now) {
        int paid = 0;
        int skipped = 0;
        int failed = 0;
        for (Long settlementId : candidateIds) {
            switch (processOne(settlementId, now)) {
                case PAID -> paid++;
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
            }
        }
        if (paid > 0 || failed > 0) {
            log.info(
                    "Settlement 예정 자동 지급 실행을 완료했습니다. "
                            + "candidates={}, paid={}, skipped={}, failed={}",
                    candidateIds.size(), paid, skipped, failed);
        }
    }

    private Outcome processOne(long settlementId, LocalDateTime now) {
        try {
            SettlementResult result = payoutService.attemptPayout(settlementId, now);
            return result == null ? Outcome.SKIPPED : Outcome.PAID;
        } catch (RuntimeException failure) {
            return recordFailure(settlementId, failure, now);
        }
    }

    private Outcome recordFailure(long settlementId, RuntimeException failure, LocalDateTime now) {
        try {
            boolean recorded = payoutService.recordFailure(settlementId, failure, now);
            if (recorded) {
                log.warn(
                        "Settlement 예정 자동 지급이 실패해 재시도 또는 최종 실패로 기록했습니다. "
                                + "settlementId={}",
                        settlementId,
                        failure);
            }
            return Outcome.FAILED;
        } catch (RuntimeException auditFailure) {
            // 감사 기록 자체가 실패해도 이 한 건만 건너뛰고 다음 후보 처리를 막지 않는다.
            log.error(
                    "Settlement 예정 자동 지급 실패 감사 기록에 실패했습니다. settlementId={}",
                    settlementId,
                    auditFailure);
            return Outcome.FAILED;
        }
    }

    private enum Outcome { PAID, SKIPPED, FAILED }
}
