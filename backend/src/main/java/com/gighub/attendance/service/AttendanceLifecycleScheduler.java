package com.gighub.attendance.service;

import com.gighub.attendance.domain.AttendanceWindowPolicy;
import com.gighub.attendance.mapper.AttendanceLifecycleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** READY·NO_SHOW·CHECK_OUT_MISSING를 서버 기준 시각으로 판정합니다. */
@Component
public class AttendanceLifecycleScheduler {

    static final int BATCH_SIZE = 100;
    static final int MAX_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(AttendanceLifecycleScheduler.class);
    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Seoul");

    private final AttendanceLifecycleMapper lifecycleMapper;
    private final AttendanceLifecycleTransitionExecutor transitionExecutor;
    private final Clock clock;

    @Autowired
    public AttendanceLifecycleScheduler(
            AttendanceLifecycleMapper lifecycleMapper,
            AttendanceLifecycleTransitionExecutor transitionExecutor) {
        this(lifecycleMapper, transitionExecutor, Clock.system(DATABASE_ZONE));
    }

    /** 경계 시각 테스트에서만 고정 Clock을 주입합니다. */
    AttendanceLifecycleScheduler(
            AttendanceLifecycleMapper lifecycleMapper,
            AttendanceLifecycleTransitionExecutor transitionExecutor,
            Clock clock) {
        this.lifecycleMapper = lifecycleMapper;
        this.transitionExecutor = transitionExecutor;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void runOnce() {
        LocalDateTime now = LocalDateTime.now(clock);

        // READY를 먼저 처리하면 중단 복구 주기에서도 막 준비된 근무를 잘못 NO_SHOW로 만들지 않습니다.
        processPhase(
                "READY",
                () -> lifecycleMapper.findReadyCandidateIds(
                        AttendanceWindowPolicy.readyLatestStartsAt(now),
                        AttendanceWindowPolicy.readyEarliestStartsAt(now),
                        BATCH_SIZE),
                now,
                transitionExecutor::advanceToReady);
        processPhase(
                "NO_SHOW",
                () -> lifecycleMapper.findNoShowCandidateIds(
                        AttendanceWindowPolicy.noShowCandidateWindow(now),
                        BATCH_SIZE),
                now,
                transitionExecutor::advanceToNoShow);
        processPhase(
                "CHECK_OUT_MISSING",
                () -> lifecycleMapper.findCheckoutMissingCandidateIds(
                        AttendanceWindowPolicy.checkOutEarliestEndsAt(now), BATCH_SIZE),
                now,
                transitionExecutor::advanceToCheckoutMissing);
    }

    private void processPhase(
            String targetStatus,
            Supplier<List<Long>> candidateLoader,
            LocalDateTime now,
            BiFunction<Long, LocalDateTime, Boolean> transition) {
        try {
            List<Long> workCaseIds = retryTransient(
                    targetStatus, null, candidateLoader);
            processCandidates(targetStatus, workCaseIds, now, transition);
        } catch (RuntimeException failure) {
            // 한 상태의 후보 조회 실패가 다른 두 상태의 처리를 막지 않게 단계별로 격리합니다.
            log.warn("근태 자동 판정 후보 조회에 실패했습니다. targetStatus={}", targetStatus, failure);
        }
    }

    private void processCandidates(
            String targetStatus,
            List<Long> workCaseIds,
            LocalDateTime now,
            BiFunction<Long, LocalDateTime, Boolean> transition) {
        int changed = 0;
        for (Long workCaseId : workCaseIds) {
            try {
                if (retryTransient(
                        targetStatus,
                        workCaseId,
                        () -> transition.apply(workCaseId, now))) {
                    changed++;
                }
            } catch (RuntimeException failure) {
                // 근무별 Transaction이므로 실패한 한 건만 다음 실행에서 다시 처리합니다.
                log.warn(
                        "근태 자동 상태 전이에 실패했습니다. targetStatus={}, workCaseId={}",
                        targetStatus,
                        workCaseId,
                        failure);
            }
        }
        if (changed > 0) {
            log.info("근태 자동 상태 전이를 완료했습니다. targetStatus={}, count={}", targetStatus, changed);
        }
    }

    private <T> T retryTransient(
            String targetStatus,
            Long workCaseId,
            Supplier<T> action) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return action.get();
            } catch (TransientDataAccessException failure) {
                if (attempt == MAX_ATTEMPTS) {
                    throw failure;
                }
                log.warn(
                        "근태 자동 판정 DB 작업을 재시도합니다. targetStatus={}, workCaseId={}, attempt={}",
                        targetStatus,
                        workCaseId,
                        attempt,
                        failure);
            }
        }
        throw new IllegalStateException("도달할 수 없는 근태 자동 판정 재시도 상태입니다.");
    }
}
