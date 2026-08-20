package com.gighub.settlement.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SettlementCalculationPolicyTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 8, 20, 9, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 8, 20, 13, 0);

    @Test
    void keepsTheWholeWageWhenThereIsNoDeductionEvenIfItIsNotATenWonMultiple() {
        SettlementCalculation result = SettlementCalculationPolicy.checkedOut(
                100_003L, START, END, 30, false, START, END);

        assertEquals(100_003L, result.getWorkerPaidAmount());
        assertEquals(0L, result.getOwnerRefundAmount());
        assertEquals(210, result.getDeductionBaseMinutes());
    }

    @Test
    void deductsThirtyLateMinutesFromTheUnpaidBreakDenominatorAndFloorsThePayout() {
        SettlementCalculation result = SettlementCalculationPolicy.checkedOut(
                100_000L, START, END, 30, false, START.plusMinutes(30), END);

        assertEquals(85_710L, result.getWorkerPaidAmount());
        assertEquals(14_290L, result.getOwnerRefundAmount());
        assertEquals(30, result.getLateMinutes());
        assertEquals(0, result.getEarlyLeaveMinutes());
    }

    @Test
    void paidBreakDoesNotReduceTheDenominator() {
        SettlementCalculation result = SettlementCalculationPolicy.checkedOut(
                100_000L, START, END, 30, true, START.plusMinutes(30), END);

        assertEquals(87_500L, result.getWorkerPaidAmount());
        assertEquals(12_500L, result.getOwnerRefundAmount());
        assertEquals(240, result.getDeductionBaseMinutes());
    }

    @Test
    void roundsAnyPositiveLateAndEarlyDifferenceUpAndCombinesThemOnce() {
        SettlementCalculation result = SettlementCalculationPolicy.checkedOut(
                100_000L,
                START,
                END,
                0,
                false,
                START.plusNanos(1),
                END.minusSeconds(60).minusNanos(1));

        assertEquals(1, result.getLateMinutes());
        assertEquals(2, result.getEarlyLeaveMinutes());
        assertEquals(98_750L, result.getWorkerPaidAmount());
        assertEquals(1_250L, result.getOwnerRefundAmount());
    }

    @Test
    void capsTheDeductionAtTheWholeWage() {
        SettlementCalculation result = SettlementCalculationPolicy.checkedOut(
                100_000L,
                START,
                END,
                0,
                false,
                START.plusHours(3),
                START.plusHours(3));

        assertEquals(0L, result.getWorkerPaidAmount());
        assertEquals(100_000L, result.getOwnerRefundAmount());
    }

    @Test
    void noShowAndCheckoutMissingProduceFullRefundSnapshots() {
        SettlementCalculation noShow = SettlementCalculationPolicy.fullRefund(
                100_000L,
                START,
                END,
                30,
                false,
                null,
                SettlementCalculationReason.NO_SHOW);
        SettlementCalculation missing = SettlementCalculationPolicy.fullRefund(
                100_000L,
                START,
                END,
                30,
                false,
                START.plusMinutes(15),
                SettlementCalculationReason.CHECK_OUT_MISSING);

        assertEquals(0L, noShow.getWorkerPaidAmount());
        assertEquals(100_000L, noShow.getOwnerRefundAmount());
        assertEquals(15, missing.getLateMinutes());
        assertEquals(100_000L, missing.getOwnerRefundAmount());
    }

    @Test
    void rejectsANonPositiveUnpaidBreakDenominator() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SettlementCalculationPolicy.checkedOut(
                        100_000L, START, END, 240, false, START, END));
    }
}
