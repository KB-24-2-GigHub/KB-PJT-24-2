package com.gighub.settlement.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.gighub.config.ApiJsonMapper;
import com.gighub.settlement.service.result.SettlementResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 최초 정산 응답의 금액 보존식과 저장 Body Replay 계약을 고정합니다. */
class SettlementReplayCodecTest {

    private static final long WAGE = 300_000L;
    private static final LocalDateTime COMPLETED_AT =
            LocalDateTime.of(2026, 8, 12, 10, 0, 0, 123_456_000);
    private static final LocalDateTime CALCULATED_AT =
            LocalDateTime.of(2026, 8, 11, 18, 0, 0, 123_456_000);

    private final SettlementReplayCodec codec = new SettlementReplayCodec();

    @Test
    void roundTripRestoresAllAmountsAndReplaysTheExactStoredBody() throws Exception {
        String storedBody = codec.writeResponseBody(
                completed(WAGE, WAGE, 210_000L, 90_000L));

        JsonNode data = ApiJsonMapper.create().readTree(storedBody).get("data");
        assertEquals(WAGE, data.get("originalEscrowAmount").longValue());
        assertEquals(210_000L, data.get("workerPaidAmount").longValue());
        assertEquals(90_000L, data.get("ownerRefundAmount").longValue());
        assertEquals(90_000L, data.get("deductionAmount").longValue());
        assertEquals(420L, data.get("deductionBaseMinutes").longValue());
        assertEquals(126L, data.get("lateMinutes").longValue());
        assertEquals(0L, data.get("earlyLeaveMinutes").longValue());
        assertEquals("CHECKED_OUT", data.get("calculationReason").textValue());
        assertEquals("ATTENDANCE_V1", data.get("calculationVersion").textValue());
        assertEquals("2026-08-11T09:00:00.123456Z", data.get("calculatedAt").textValue());
        assertTrue(data.get("settlementAmount") == null);

        SettlementResult replay = codec.readResponseBody(storedBody);

        assertEquals(WAGE, replay.getSettlementAmount());
        assertEquals(WAGE, replay.getOriginalEscrowAmount());
        assertEquals(210_000L, replay.getWorkerPaidAmount());
        assertEquals(90_000L, replay.getOwnerRefundAmount());
        assertEquals(90_000L, replay.getDeductionAmount());
        assertEquals(420L, replay.getDeductionBaseMinutes());
        assertEquals(126L, replay.getLateMinutes());
        assertEquals(0L, replay.getEarlyLeaveMinutes());
        assertEquals("CHECKED_OUT", replay.getCalculationReason());
        assertEquals("ATTENDANCE_V1", replay.getCalculationVersion());
        assertEquals(CALCULATED_AT, replay.getCalculatedAt());
        assertTrue(replay.isReplayed());
        assertEquals(storedBody, codec.writeResponseBody(replay));
    }

    @Test
    void refundRoundTripRestoresTheFullOwnerRefund() {
        SettlementResult refund = SettlementResult.builder()
                .settlementId(12L)
                .status("REFUNDED")
                .settlementAmount(WAGE)
                .originalEscrowAmount(WAGE)
                .workerPaidAmount(0L)
                .ownerRefundAmount(WAGE)
                .deductionAmount(WAGE)
                .deductionBaseMinutes(420L)
                .lateMinutes(0L)
                .earlyLeaveMinutes(0L)
                .calculationReason("NO_SHOW")
                .calculationVersion("ATTENDANCE_V1")
                .calculatedAt(CALCULATED_AT)
                .completedAt(COMPLETED_AT)
                .replayed(false)
                .build();

        SettlementResult replay = codec.readResponseBody(codec.writeResponseBody(refund));

        assertEquals("REFUNDED", replay.getStatus());
        assertEquals(0L, replay.getWorkerPaidAmount());
        assertEquals(WAGE, replay.getOwnerRefundAmount());
        assertEquals(WAGE, replay.getDeductionAmount());
        assertEquals("NO_SHOW", replay.getCalculationReason());
        assertTrue(replay.isReplayed());
    }

    @Test
    void readsStoredBodyWrittenBeforeCalculationFieldsWereIntroduced() {
        SettlementResult replay = codec.readResponseBody(
                bodyWithOutcome("COMPLETED", WAGE, WAGE, 0L));

        assertEquals(WAGE, replay.getSettlementAmount());
        assertEquals(0L, replay.getDeductionAmount());
        assertNull(replay.getDeductionBaseMinutes());
        assertNull(replay.getLateMinutes());
        assertNull(replay.getEarlyLeaveMinutes());
        assertNull(replay.getCalculationReason());
        assertNull(replay.getCalculationVersion());
        assertNull(replay.getCalculatedAt());
        assertTrue(replay.isReplayed());
    }

    @Test
    void readFailsClosedWhenOnlySomeCalculationFieldsAreStored() {
        String partialBody = bodyWithOutcome("COMPLETED", WAGE, WAGE, 0L)
                .replace(",\"completedAt\"", ",\"deductionAmount\":0,\"completedAt\"");

        assertThrows(
                IllegalStateException.class,
                () -> codec.readResponseBody(partialBody));
    }

    @Test
    void writeFailsClosedWhenOriginalDoesNotMatchSettlementAmount() {
        assertThrows(
                IllegalStateException.class,
                () -> codec.writeResponseBody(completed(WAGE, WAGE - 1, WAGE - 1, 0L)));
    }

    @Test
    void readFailsClosedForMissingNegativeOrNonConservingAmounts() {
        assertThrows(
                IllegalStateException.class,
                () -> codec.readResponseBody(bodyWithoutOwnerRefund()));
        assertThrows(
                IllegalStateException.class,
                () -> codec.readResponseBody(bodyWithAmounts(WAGE, -1L, WAGE + 1L)));
        assertThrows(
                IllegalStateException.class,
                () -> codec.readResponseBody(bodyWithAmounts(WAGE, WAGE - 1L, 0L)));
        assertThrows(
                IllegalStateException.class,
                () -> codec.readResponseBody(
                        bodyWithOutcome("REFUNDED", WAGE, WAGE, 0L)));
    }

    private SettlementResult completed(
            long settlementAmount,
            long originalEscrowAmount,
            long workerPaidAmount,
            long ownerRefundAmount) {
        return SettlementResult.builder()
                .settlementId(12L)
                .status("COMPLETED")
                .settlementAmount(settlementAmount)
                .originalEscrowAmount(originalEscrowAmount)
                .workerPaidAmount(workerPaidAmount)
                .ownerRefundAmount(ownerRefundAmount)
                .deductionAmount(ownerRefundAmount)
                .deductionBaseMinutes(420L)
                .lateMinutes(126L)
                .earlyLeaveMinutes(0L)
                .calculationReason("CHECKED_OUT")
                .calculationVersion("ATTENDANCE_V1")
                .calculatedAt(CALCULATED_AT)
                .completedAt(COMPLETED_AT)
                .replayed(false)
                .build();
    }

    private String bodyWithoutOwnerRefund() {
        return "{\"data\":{\"settlementId\":12,\"status\":\"COMPLETED\","
                + "\"originalEscrowAmount\":300000,\"workerPaidAmount\":300000,"
                + "\"completedAt\":\"2026-08-12T01:00:00Z\"}}";
    }

    private String bodyWithAmounts(long original, long workerPaid, long ownerRefund) {
        return bodyWithOutcome("COMPLETED", original, workerPaid, ownerRefund);
    }

    private String bodyWithOutcome(
            String status, long original, long workerPaid, long ownerRefund) {
        return "{\"data\":{\"settlementId\":12,\"status\":\"" + status + "\","
                + "\"originalEscrowAmount\":" + original
                + ",\"workerPaidAmount\":" + workerPaid
                + ",\"ownerRefundAmount\":" + ownerRefund
                + ",\"completedAt\":\"2026-08-12T01:00:00Z\"}}";
    }
}
