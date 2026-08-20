package com.gighub.work.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExpectedNetAmountTest {

    @Test
    void belowDeductionThresholdHasNoTax() {
        assertEquals(140_000L, ExpectedNetAmount.calculate(140_000L));
    }

    @Test
    void waivesTaxWhenIncomeTaxIsBelowOneThousandWon() {
        // taxableBase=36000 -> incomeTax=36000*0.027=972원, 10원 미만 절사해도 970 < 1000이라 전액 면제
        long dailyWage = 150_000L + 36_000L;
        assertEquals(dailyWage, ExpectedNetAmount.calculate(dailyWage));
    }

    @Test
    void deductsIncomeAndLocalIncomeTaxAboveWaiverThreshold() {
        // taxableBase=50000 -> incomeTax=1350(10원 단위 그대로), localIncomeTax=135->130으로 절사
        long dailyWage = 150_000L + 50_000L;
        assertEquals(dailyWage - 1350L - 130L, ExpectedNetAmount.calculate(dailyWage));
    }

    @Test
    void calculatesTheReferenceWithoutOverflowAtTheLongBoundary() {
        assertEquals(8_949_437_887_360_193_437L,
                ExpectedNetAmount.calculate(Long.MAX_VALUE));
    }
}
