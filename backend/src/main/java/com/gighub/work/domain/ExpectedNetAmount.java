package com.gighub.work.domain;

import java.math.BigInteger;

/**
 * 일용근로소득 원천징수 기준의 예상 실수령액 참고 계산입니다.
 *
 * <p>REQUIREMENTS {@code DASH-003}이 고정한 계약을 그대로 옮깁니다.</p>
 *
 * <pre>{@code
 * taxableBase = max(dailyWage - 150000, 0)
 * incomeTax = taxableBase × 0.027, 10원 미만 절사
 * localIncomeTax = taxableBase × 0.0027, 10원 미만 절사
 * if incomeTax < 1000: incomeTax = 0; localIncomeTax = 0
 * expectedNetAmount = dailyWage - incomeTax - localIncomeTax
 * }</pre>
 *
 * <p>실제 정산 세액 범위는 별도 승인된 계약을 따르며, 이 값은 화면 참고용입니다. 부동소수점
 * 오차를 피하려고 정수 나눗셈으로만 계산합니다. {@code taxableBase}가 음이 아니므로 Java의
 * 정수 나눗셈 절삭이 곧 내림입니다.</p>
 */
public final class ExpectedNetAmount {

    private static final long DEDUCTION_THRESHOLD = 150_000L;
    private static final long INCOME_TAX_WAIVER_THRESHOLD = 1_000L;
    private static final BigInteger TEN = BigInteger.TEN;

    private ExpectedNetAmount() {
    }

    public static long calculate(long dailyWage) {
        long taxableBase = Math.max(dailyWage - DEDUCTION_THRESHOLD, 0L);
        long incomeTax = calculateTax(taxableBase, 1_000L);
        long localIncomeTax = calculateTax(taxableBase, 10_000L);
        if (incomeTax < INCOME_TAX_WAIVER_THRESHOLD) {
            incomeTax = 0L;
            localIncomeTax = 0L;
        }
        return dailyWage - incomeTax - localIncomeTax;
    }

    /** BIGINT 범위의 지급액도 세율 곱셈 중 overflow 없이 10원 미만을 버립니다. */
    private static long calculateTax(long taxableBase, long denominator) {
        return BigInteger.valueOf(taxableBase)
                .multiply(BigInteger.valueOf(27L))
                .divide(BigInteger.valueOf(denominator))
                .divide(TEN)
                .multiply(TEN)
                .longValueExact();
    }
}
