package com.gighub.work.domain;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkCaseTimesTest {

    private static final LocalDate WORK_DATE = LocalDate.of(2026, 8, 10);

    @Test
    void combinesWithoutShiftingTheWallClockValue() {
        LocalDateTime combined = WorkCaseTimes.combine(WORK_DATE, LocalTime.of(9, 0));

        assertEquals(LocalDateTime.of(2026, 8, 10, 9, 0), combined);
    }

    @Test
    void rejectsNullInput() {
        assertThrows(
                NullPointerException.class,
                () -> WorkCaseTimes.combine(null, LocalTime.of(9, 0))
        );
        assertThrows(
                NullPointerException.class,
                () -> WorkCaseTimes.combine(WORK_DATE, null)
        );
    }

    /**
     * {@code combineEnd} 는 세 인자를 모두 요구한다. {@code workDate}·{@code startTime} 은
     * {@code combine} 에는 없던 새 전제조건이다 — 앞은 다음 날 결합, 뒤는 순서 판정에 쓰인다.
     *
     * <p>어느 인자였는지까지 확인한다. 가드를 지워도 {@code java.time} 안쪽에서 결국
     * {@code NullPointerException} 이 나므로, 예외 타입만 보면 가드가 사라진 것을 잡지
     * 못한다.</p>
     */
    @Test
    void namesTheMissingArgumentOfCombineEnd() {
        assertEquals("workDate", assertThrows(
                NullPointerException.class,
                () -> WorkCaseTimes.combineEnd(null, LocalTime.of(9, 0), LocalTime.of(18, 0))
        ).getMessage());
        assertEquals("startTime", assertThrows(
                NullPointerException.class,
                () -> WorkCaseTimes.combineEnd(WORK_DATE, null, LocalTime.of(18, 0))
        ).getMessage());
        assertEquals("endTime", assertThrows(
                NullPointerException.class,
                () -> WorkCaseTimes.combineEnd(WORK_DATE, LocalTime.of(9, 0), null)
        ).getMessage());
    }

    @Test
    void keepsSameDayWhenEndIsAfterStart() {
        LocalDateTime endsAt = WorkCaseTimes.combineEnd(
                WORK_DATE, LocalTime.of(9, 0), LocalTime.of(18, 0));

        assertEquals(LocalDateTime.of(2026, 8, 10, 18, 0), endsAt);
    }

    /** SPEC-413-01 — 자정을 넘기는 야간 근무. workDate 는 근무가 <b>시작</b>하는 날이다. */
    @Test
    void movesEndToNextDayWhenEndIsBeforeStart() {
        LocalDateTime endsAt = WorkCaseTimes.combineEnd(
                WORK_DATE, LocalTime.of(23, 0), LocalTime.of(1, 0));

        assertEquals(LocalDateTime.of(2026, 8, 11, 1, 0), endsAt);
    }

    /**
     * 시작과 종료가 같으면 0분이 아니라 24시간으로 본다.
     *
     * <p>0분 근무로 접으면 {@code 09:00~09:00} 오타가 저장 가능한 값이 된다. 24시간으로 두면
     * 길이 상한이 걸러 낸다 — 아래 {@link #rejectsWorkPeriodLongerThanTheCap} 참고.</p>
     */
    @Test
    void treatsEqualStartAndEndAsFullDay() {
        LocalDateTime startsAt = WorkCaseTimes.combine(WORK_DATE, LocalTime.of(9, 0));
        LocalDateTime endsAt = WorkCaseTimes.combineEnd(
                WORK_DATE, LocalTime.of(9, 0), LocalTime.of(9, 0));

        assertEquals(LocalDateTime.of(2026, 8, 11, 9, 0), endsAt);
        assertEquals(Duration.ofHours(24), Duration.between(startsAt, endsAt));
    }

    /**
     * {@code combineEnd} 결과는 어떤 입력에서도 시작보다 뒤다 —
     * {@code ck_work_cases_time CHECK (ends_at > starts_at)} 이 그대로 성립한다.
     */
    @Test
    void combinedEndIsAlwaysAfterStart() {
        LocalTime start = LocalTime.of(22, 30);
        LocalTime[] ends = {
                LocalTime.of(23, 59), // 같은 날
                LocalTime.of(22, 31), // 같은 날, 1분 뒤
                LocalTime.of(22, 30), // 동일 → 다음 날
                LocalTime.of(22, 29), // 이른 시각 → 다음 날
                LocalTime.MIDNIGHT,   // 자정 → 다음 날
                LocalTime.of(6, 0)    // 다음 날 아침
        };

        LocalDateTime startsAt = WorkCaseTimes.combine(WORK_DATE, start);
        for (LocalTime end : ends) {
            LocalDateTime endsAt = WorkCaseTimes.combineEnd(WORK_DATE, start, end);
            assertTrue(
                    WorkCaseTimes.endsAfterStart(startsAt, endsAt),
                    () -> "종료 " + end + " 가 시작보다 뒤여야 한다: " + endsAt
            );
        }
    }

    @Test
    void detectsNonPositiveDuration() {
        LocalDateTime startsAt = LocalDateTime.of(2026, 8, 10, 9, 0);

        assertTrue(WorkCaseTimes.endsAfterStart(startsAt, startsAt.plusHours(8)));
        assertFalse(WorkCaseTimes.endsAfterStart(startsAt, startsAt));
        assertFalse(WorkCaseTimes.endsAfterStart(startsAt, startsAt.minusMinutes(1)));
    }

    /** 상한값 자체는 허용한다(경계 포함). */
    @Test
    void allowsWorkPeriodExactlyAtTheCap() {
        LocalDateTime startsAt = LocalDateTime.of(2026, 8, 10, 20, 0);

        assertTrue(WorkCaseTimes.withinMaxDuration(
                startsAt, startsAt.plus(WorkCaseTimes.MAX_WORK_DURATION)));
    }

    @Test
    void rejectsWorkPeriodLongerThanTheCap() {
        LocalDateTime startsAt = LocalDateTime.of(2026, 8, 10, 20, 0);

        assertFalse(WorkCaseTimes.withinMaxDuration(
                startsAt, startsAt.plus(WorkCaseTimes.MAX_WORK_DURATION).plusMinutes(1)));
        // 시작과 종료가 같은 오타(24시간)가 상한에서 걸리는 경로.
        assertFalse(WorkCaseTimes.withinMaxDuration(startsAt, startsAt.plusHours(24)));
    }

    /** 자정을 넘기는 실제 야간 근무는 상한 안에 들어온다. */
    @Test
    void allowsTypicalOvernightShift() {
        LocalDateTime startsAt = WorkCaseTimes.combine(WORK_DATE, LocalTime.of(23, 0));
        LocalDateTime endsAt = WorkCaseTimes.combineEnd(
                WORK_DATE, LocalTime.of(23, 0), LocalTime.of(7, 0));

        assertEquals(Duration.ofHours(8), Duration.between(startsAt, endsAt));
        assertTrue(WorkCaseTimes.withinMaxDuration(startsAt, endsAt));
    }
}
