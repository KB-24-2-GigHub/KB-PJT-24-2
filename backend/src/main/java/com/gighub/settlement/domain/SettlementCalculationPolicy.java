package com.gighub.settlement.domain;

import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDateTime;

/** 저장·시계·Wallet에 의존하지 않고 승인된 근태 비례 정산 공식만 계산합니다. */
public final class SettlementCalculationPolicy {

    public static final String VERSION = "ATTENDANCE_V1";
    private static final long NANOS_PER_MINUTE = 60_000_000_000L;

    private SettlementCalculationPolicy() {
    }

    public static SettlementCalculation checkedOut(
            long agreedWage,
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            int breakMinutes,
            boolean breakPaid,
            LocalDateTime checkedInAt,
            LocalDateTime checkedOutAt) {
        requirePositiveWage(agreedWage);
        if (checkedInAt == null || checkedOutAt == null || checkedOutAt.isBefore(checkedInAt)) {
            throw new IllegalArgumentException("성공 출퇴근 시각이 올바르지 않습니다.");
        }

        long baseMinutes = deductionBaseMinutes(
                startsAt, endsAt, breakMinutes, breakPaid);
        long lateMinutes = ceilPositiveMinutes(startsAt, checkedInAt);
        long earlyLeaveMinutes = ceilPositiveMinutes(checkedOutAt, endsAt);
        long deductedMinutes = Math.min(
                (long) baseMinutes,
                (long) lateMinutes + earlyLeaveMinutes);

        long workerPaidAmount = deductedMinutes == 0
                ? agreedWage
                : floorToTenWon(proportionalAmount(
                        agreedWage, baseMinutes - deductedMinutes, baseMinutes));
        return calculation(
                agreedWage,
                workerPaidAmount,
                baseMinutes,
                lateMinutes,
                earlyLeaveMinutes,
                SettlementCalculationReason.CHECKED_OUT);
    }

    public static SettlementCalculation fullRefund(
            long agreedWage,
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            int breakMinutes,
            boolean breakPaid,
            LocalDateTime checkedInAt,
            SettlementCalculationReason reason) {
        requirePositiveWage(agreedWage);
        if (reason != SettlementCalculationReason.NO_SHOW
                && reason != SettlementCalculationReason.CHECK_OUT_MISSING) {
            throw new IllegalArgumentException("전액 환불 Snapshot 사유가 올바르지 않습니다.");
        }
        if ((reason == SettlementCalculationReason.NO_SHOW && checkedInAt != null)
                || (reason == SettlementCalculationReason.CHECK_OUT_MISSING
                && checkedInAt == null)) {
            throw new IllegalArgumentException("근태 결말과 성공 출근 시각이 일치하지 않습니다.");
        }

        long baseMinutes = deductionBaseMinutes(
                startsAt, endsAt, breakMinutes, breakPaid);
        long lateMinutes = checkedInAt == null
                ? 0
                : ceilPositiveMinutes(startsAt, checkedInAt);
        return calculation(
                agreedWage,
                0L,
                baseMinutes,
                lateMinutes,
                0,
                reason);
    }

    private static SettlementCalculation calculation(
            long agreedWage,
            long workerPaidAmount,
            long baseMinutes,
            long lateMinutes,
            long earlyLeaveMinutes,
            SettlementCalculationReason reason) {
        long ownerRefundAmount = Math.subtractExact(agreedWage, workerPaidAmount);
        return SettlementCalculation.builder()
                .originalAmount(agreedWage)
                .workerPaidAmount(workerPaidAmount)
                .ownerRefundAmount(ownerRefundAmount)
                .deductionBaseMinutes(baseMinutes)
                .lateMinutes(lateMinutes)
                .earlyLeaveMinutes(earlyLeaveMinutes)
                .reason(reason)
                .version(VERSION)
                .build();
    }

    private static long deductionBaseMinutes(
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            int breakMinutes,
            boolean breakPaid) {
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("예정 근무 시각이 올바르지 않습니다.");
        }
        if (breakMinutes < 0) {
            throw new IllegalArgumentException("휴게 시간은 음수일 수 없습니다.");
        }

        long scheduledMinutes = Duration.between(startsAt, endsAt).toMinutes();
        if (scheduledMinutes <= 0) {
            throw new IllegalArgumentException("예정 근무 분수가 허용 범위를 벗어납니다.");
        }
        long baseMinutes = scheduledMinutes - (breakPaid ? 0L : breakMinutes);
        if (baseMinutes <= 0) {
            throw new IllegalArgumentException("차감 분모는 양수여야 합니다.");
        }
        return baseMinutes;
    }

    /** 양의 초·마이크로초가 조금이라도 있으면 다음 정수 분으로 올립니다. */
    private static long ceilPositiveMinutes(
            LocalDateTime boundaryStart, LocalDateTime boundaryEnd) {
        if (!boundaryEnd.isAfter(boundaryStart)) {
            return 0;
        }
        long nanos = Duration.between(boundaryStart, boundaryEnd).toNanos();
        long minutes = Math.floorDiv(nanos - 1L, NANOS_PER_MINUTE) + 1L;
        return minutes;
    }

    private static long proportionalAmount(long amount, long remaining, long base) {
        return BigInteger.valueOf(amount)
                .multiply(BigInteger.valueOf(remaining))
                .divide(BigInteger.valueOf(base))
                .longValueExact();
    }

    private static long floorToTenWon(long amount) {
        return Math.floorDiv(amount, 10L) * 10L;
    }

    private static void requirePositiveWage(long agreedWage) {
        if (agreedWage <= 0) {
            throw new IllegalArgumentException("약정 일급은 양수여야 합니다.");
        }
    }
}
