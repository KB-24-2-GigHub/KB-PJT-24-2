package com.gighub.settlement.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.gighub.config.ApiJsonMapper;
import com.gighub.settlement.service.result.SettlementResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 최초 정산 응답의 금액 보존식과 저장 Body Replay 계약을 고정합니다. */
class SettlementReplayCodecTest {

    private static final long WAGE = 300_000L;
    private static final LocalDateTime COMPLETED_AT =
            LocalDateTime.of(2026, 8, 12, 10, 0, 0, 123_456_000);

    private final SettlementReplayCodec codec = new SettlementReplayCodec();

    @Test
    void roundTripRestoresAllAmountsAndReplaysTheExactStoredBody() throws Exception {
        String storedBody = codec.writeResponseBody(completed(WAGE, WAGE, WAGE, 0L));

        JsonNode data = ApiJsonMapper.create().readTree(storedBody).get("data");
        assertEquals(WAGE, data.get("originalEscrowAmount").longValue());
        assertEquals(WAGE, data.get("workerPaidAmount").longValue());
        assertEquals(0L, data.get("ownerRefundAmount").longValue());
        assertTrue(data.get("settlementAmount") == null);

        SettlementResult replay = codec.readResponseBody(storedBody);

        assertEquals(WAGE, replay.getSettlementAmount());
        assertEquals(WAGE, replay.getOriginalEscrowAmount());
        assertEquals(WAGE, replay.getWorkerPaidAmount());
        assertEquals(0L, replay.getOwnerRefundAmount());
        assertTrue(replay.isReplayed());
        assertEquals(storedBody, codec.writeResponseBody(replay));
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
        return "{\"data\":{\"settlementId\":12,\"status\":\"COMPLETED\","
                + "\"originalEscrowAmount\":" + original
                + ",\"workerPaidAmount\":" + workerPaid
                + ",\"ownerRefundAmount\":" + ownerRefund
                + ",\"completedAt\":\"2026-08-12T01:00:00Z\"}}";
    }
}
