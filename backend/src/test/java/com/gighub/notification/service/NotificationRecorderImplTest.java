package com.gighub.notification.service;

import com.gighub.notification.domain.NotificationType;
import com.gighub.notification.service.command.NotificationRecordCommand;
import com.gighub.notification.service.impl.NotificationRecorderImpl;
import com.gighub.notification.sse.NotificationEmitterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 알림 적재가 원인 도메인 Transaction과 맺는 관계를 고정한다(SPEC-384-01).
 *
 * <p>가장 중요한 계약은 두 가지다. 적재가 Commit 이후에 일어나 Rollback되면 알림이 남지 않고,
 * 적재 실패가 호출자에게 전파되지 않아 이미 Commit된 자금 처리를 실패로 바꾸지 않는다.</p>
 */
class NotificationRecorderImplTest {

    private static final long WORK_CASE_ID = 77L;
    private static final long OWNER_ID = 11L;
    private static final long WORKER_ID = 22L;

    private final NotificationRecordTransaction recordTransaction =
            mock(NotificationRecordTransaction.class);
    private final NotificationEmitterRegistry emitterRegistry = mock(NotificationEmitterRegistry.class);
    private final NotificationRecorder recorder =
            new NotificationRecorderImpl(recordTransaction, emitterRegistry);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void doesNotWriteUntilTheDomainTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();

        recorder.record(command(NotificationType.SETTLED, 501L, List.of(OWNER_ID, WORKER_ID)));

        verifyNoInteractions(recordTransaction);
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
    }

    @Test
    void writesOneRowPerRecipientAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        NotificationRecordCommand command =
                command(NotificationType.SETTLED, 501L, List.of(OWNER_ID, WORKER_ID));

        recorder.record(command);
        commit();

        // 한 행을 둘이 공유하면 한쪽의 읽음 처리가 다른 쪽 상태를 덮어쓴다.
        verify(recordTransaction).insertOne(command, OWNER_ID);
        verify(recordTransaction).insertOne(command, WORKER_ID);
    }

    @Test
    void rollbackLeavesNoNotification() {
        TransactionSynchronizationManager.initSynchronization();

        recorder.record(command(NotificationType.SETTLED, 501L, List.of(OWNER_ID)));
        // Rollback이면 afterCommit이 호출되지 않는다.
        TransactionSynchronizationManager.clearSynchronization();

        verifyNoInteractions(recordTransaction);
    }

    /**
     * 적재 실패가 호출자에게 전파되면 이미 Commit된 정산이 사용자에게 실패로 보인다.
     * P1 알림이 P0 자금 처리의 성공 여부를 바꾸지 않아야 한다.
     */
    @Test
    void swallowsWriteFailureSoTheCommittedDomainWorkStands() {
        TransactionSynchronizationManager.initSynchronization();
        doThrow(new DataIntegrityViolationException("content too long"))
                .when(recordTransaction).insertOne(any(), eq(OWNER_ID));

        recorder.record(command(NotificationType.SETTLED, 501L, List.of(OWNER_ID, WORKER_ID)));

        assertDoesNotThrow(this::commit);
        // 한 수신자의 실패가 나머지 수신자의 알림까지 막지 않는다.
        verify(recordTransaction).insertOne(any(), eq(WORKER_ID));
    }

    @Test
    void treatsDuplicateEventAsAlreadyRecorded() {
        TransactionSynchronizationManager.initSynchronization();
        doThrow(new DuplicateKeyException("uk_notifications_event"))
                .when(recordTransaction).insertOne(any(), eq(OWNER_ID));

        recorder.record(command(NotificationType.SETTLED, 501L, List.of(OWNER_ID, WORKER_ID)));

        assertDoesNotThrow(this::commit);
        verify(recordTransaction).insertOne(any(), eq(WORKER_ID));
    }

    /** Transaction 밖 호출은 미룰 Commit이 없으므로 바로 적재한다. */
    @Test
    void writesImmediatelyWithoutAnActiveTransaction() {
        NotificationRecordCommand command =
                command(NotificationType.DOC_SHARED, 900L, List.of(OWNER_ID));

        recorder.record(command);

        verify(recordTransaction).insertOne(command, OWNER_ID);
    }

    @Test
    void rejectsCommandWithoutRecipients() {
        TransactionSynchronizationManager.initSynchronization();

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> recorder.record(command(NotificationType.SETTLED, 501L, List.of())));

        verify(recordTransaction, never()).insertOne(any(), any());
    }

    /**
     * 실시간 신호는 적재가 확정된 수신자에게만 간다(#386).
     *
     * <p>실패한 수신자에게도 보내면 "새 알림이 있다"는 신호 뒤에 목록이 그대로인 화면이 남는다.</p>
     */
    @Test
    void signalsOnlyRecipientsWhoseRowWasWritten() {
        TransactionSynchronizationManager.initSynchronization();
        doThrow(new DataIntegrityViolationException("content too long"))
                .when(recordTransaction).insertOne(any(), eq(OWNER_ID));

        recorder.record(command(NotificationType.SETTLED, 501L, List.of(OWNER_ID, WORKER_ID)));
        commit();

        verify(emitterRegistry, never()).notifyRecipient(OWNER_ID);
        verify(emitterRegistry).notifyRecipient(WORKER_ID);
    }

    /** SSE는 전달 수단이다. 신호가 실패해도 나머지 수신자의 적재가 멈추지 않는다. */
    @Test
    void keepsRecordingWhenTheRealtimeSignalFails() {
        TransactionSynchronizationManager.initSynchronization();
        doThrow(new IllegalStateException("emitter closed"))
                .when(emitterRegistry).notifyRecipient(OWNER_ID);

        recorder.record(command(NotificationType.SETTLED, 501L, List.of(OWNER_ID, WORKER_ID)));

        assertDoesNotThrow(this::commit);
        verify(recordTransaction).insertOne(any(), eq(WORKER_ID));
    }

    private void commit() {
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
    }

    private static NotificationRecordCommand command(
            NotificationType type,
            long sourceId,
            List<Long> recipients) {
        return NotificationRecordCommand.builder()
                .type(type)
                .sourceId(sourceId)
                .workCaseId(WORK_CASE_ID)
                .workCaseTitle("주말 홀서빙")
                .recipientUserIds(recipients)
                .build();
    }
}
