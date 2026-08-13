package com.gighub.settlement.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * #172 Scheduler의 실행 주기·배치 크기를 외부 설정에서 읽습니다.
 *
 * <p>재시도 간격·최대 시도 횟수는 SETTLE-003이 고정한 계약값이라 여기서 다루지 않고
 * {@code SettlementRetryPolicy}에 고정돼 있습니다. 이 클래스는 운영 환경마다 조정할 수 있는
 * 실행 주기·배치 처리량만 다룹니다.</p>
 *
 * <p>값이 없으면 안전한 기본값을 쓰므로 {@code gighub.database.config} 외부 파일에 이 Key들이
 * 없어도 애플리케이션은 정상 기동합니다.</p>
 */
@Component
public class SettlementSchedulerProperties {

    public static final String BATCH_SIZE_KEY = "settlement.scheduler.batch-size";
    public static final String FIXED_DELAY_MS_KEY = "settlement.scheduler.fixed-delay-ms";
    public static final String INITIAL_DELAY_MS_KEY = "settlement.scheduler.initial-delay-ms";

    public static final int DEFAULT_BATCH_SIZE = 100;
    public static final long DEFAULT_FIXED_DELAY_MS = 60_000L;
    public static final long DEFAULT_INITIAL_DELAY_MS = 60_000L;

    private final int batchSize;
    private final Duration fixedDelay;
    private final Duration initialDelay;

    @Autowired
    public SettlementSchedulerProperties(Environment environment) {
        this(
                environment.getProperty(BATCH_SIZE_KEY, Integer.class, DEFAULT_BATCH_SIZE),
                environment.getProperty(FIXED_DELAY_MS_KEY, Long.class, DEFAULT_FIXED_DELAY_MS),
                environment.getProperty(INITIAL_DELAY_MS_KEY, Long.class, DEFAULT_INITIAL_DELAY_MS));
    }

    SettlementSchedulerProperties(int batchSize, long fixedDelayMs, long initialDelayMs) {
        this.batchSize = requirePositive(BATCH_SIZE_KEY, batchSize);
        this.fixedDelay = Duration.ofMillis(requirePositive(FIXED_DELAY_MS_KEY, fixedDelayMs));
        this.initialDelay = Duration.ofMillis(requirePositive(INITIAL_DELAY_MS_KEY, initialDelayMs));
    }

    /** SETTLE-003의 "최대 100건" 상한 안에서 한 실행이 훑을 후보 수입니다. */
    public int getBatchSize() {
        return batchSize;
    }

    /** 한 실행이 끝난 뒤 다음 실행까지 대기하는 간격입니다. */
    public Duration getFixedDelay() {
        return fixedDelay;
    }

    /** 애플리케이션 기동 뒤 첫 실행까지 대기하는 간격입니다. */
    public Duration getInitialDelay() {
        return initialDelay;
    }

    private static int requirePositive(String key, int value) {
        if (value <= 0) {
            throw new IllegalStateException(key + "는 1 이상이어야 합니다.");
        }
        return value;
    }

    private static long requirePositive(String key, long value) {
        if (value <= 0) {
            throw new IllegalStateException(key + "는 1 이상이어야 합니다.");
        }
        return value;
    }
}
