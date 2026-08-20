package com.gighub.settlement.service;

import com.gighub.settlement.domain.SettlementCalculation;
import com.gighub.settlement.domain.SettlementCalculationReason;
import com.gighub.settlement.mapper.SettlementMapper;
import com.gighub.settlement.service.command.SettlementCalculationCommand;
import com.gighub.settlement.service.impl.SettlementReservationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementReservationServiceImplTest {

    private static final LocalDateTime START =
            LocalDateTime.of(2026, 8, 20, 9, 0);
    private static final LocalDateTime END = START.plusHours(4);

    @Mock
    private SettlementMapper settlementMapper;

    private SettlementReservationService service;

    @BeforeEach
    void setUp() {
        service = new SettlementReservationServiceImpl(settlementMapper);
    }

    @Test
    void calculatesAndStoresTheCheckoutSnapshotTogetherWithTheSchedule() {
        LocalDateTime dueAt = END.plusHours(24);
        SettlementCalculationCommand command = command(
                SettlementCalculationReason.CHECKED_OUT,
                START.plusMinutes(30),
                END);
        when(settlementMapper.scheduleWaitingPayout(eq(11L), eq(dueAt), any()))
                .thenReturn(1);

        service.schedulePayout(command, dueAt);

        ArgumentCaptor<SettlementCalculation> calculation =
                ArgumentCaptor.forClass(SettlementCalculation.class);
        verify(settlementMapper).scheduleWaitingPayout(
                eq(11L), eq(dueAt), calculation.capture());
        assertEquals(85_710L, calculation.getValue().getWorkerPaidAmount());
        assertEquals(14_290L, calculation.getValue().getOwnerRefundAmount());
        assertEquals(210L, calculation.getValue().getDeductionBaseMinutes());
    }

    @Test
    void recordsCheckoutMissingAsAFullRefundSnapshot() {
        SettlementCalculationCommand command = command(
                SettlementCalculationReason.CHECK_OUT_MISSING,
                START.plusMinutes(15),
                null);
        when(settlementMapper.recordWaitingTerminalSnapshot(eq(11L), any()))
                .thenReturn(1);

        service.recordTerminalSnapshot(command);

        ArgumentCaptor<SettlementCalculation> calculation =
                ArgumentCaptor.forClass(SettlementCalculation.class);
        verify(settlementMapper).recordWaitingTerminalSnapshot(
                eq(11L), calculation.capture());
        assertEquals(0L, calculation.getValue().getWorkerPaidAmount());
        assertEquals(100_000L, calculation.getValue().getOwnerRefundAmount());
        assertEquals(15L, calculation.getValue().getLateMinutes());
    }

    @Test
    void failsClosedWhenTheImmutableSnapshotWasAlreadyWritten() {
        SettlementCalculationCommand command = command(
                SettlementCalculationReason.NO_SHOW, null, null);

        assertThrows(
                IllegalStateException.class,
                () -> service.recordTerminalSnapshot(command));
    }

    private SettlementCalculationCommand command(
            SettlementCalculationReason reason,
            LocalDateTime checkedInAt,
            LocalDateTime checkedOutAt) {
        return SettlementCalculationCommand.builder()
                .workCaseId(11L)
                .agreedWage(100_000L)
                .startsAt(START)
                .endsAt(END)
                .breakMinutes(30)
                .breakPaid(false)
                .checkedInAt(checkedInAt)
                .checkedOutAt(checkedOutAt)
                .reason(reason)
                .build();
    }
}
