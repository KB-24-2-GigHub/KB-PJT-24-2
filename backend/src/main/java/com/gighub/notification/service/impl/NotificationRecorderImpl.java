package com.gighub.notification.service.impl;

import com.gighub.notification.service.NotificationRecordTransaction;
import com.gighub.notification.service.NotificationRecorder;
import com.gighub.notification.service.command.NotificationRecordCommand;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;

/**
 * 알림 적재를 원인 도메인 Commit 뒤로 미뤄 P1 알림이 P0 자금 처리를 막지 못하게 합니다.
 *
 * <p>왜 참여가 아니라 Commit 이후인가 — 알림 Table의 제약, 데이터 길이, Mapper·배포 불일치가
 * 예치·정산·환불을 중단시키는 가용성 장애로 번지면 안 됩니다. ALERT-002가 요구하는 방향은
 * "도메인이 실패하면 알림을 확정하지 않는다" 하나뿐이며, 그 역방향 원자성은 요구하지
 * 않습니다(SPEC-384-01).</p>
 *
 * <p>대가는 알림 유실입니다. 이 MVP에는 Outbox와 자동 재시도가 없으므로 실패를 삼키는 대신
 * 어떤 이벤트가 빠졌는지 특정할 수 있게 기록만 남깁니다. 알림 본문과 개인 식별 정보는 남기지
 * 않습니다.</p>
 */
@Service
@RequiredArgsConstructor
public class NotificationRecorderImpl implements NotificationRecorder {

    private static final Logger log = LoggerFactory.getLogger(NotificationRecorderImpl.class);

    private final NotificationRecordTransaction recordTransaction;

    @Override
    public void record(NotificationRecordCommand command) {
        NotificationRecordCommand validated = validate(command);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // Rollback되면 afterCommit이 호출되지 않아 알림도 남지 않는다.
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            recordSafely(validated);
                        }
                    });
            return;
        }
        // Transaction 밖 호출(Scheduler 뒷정리 등)은 미룰 Commit이 없으므로 바로 적재한다.
        recordSafely(validated);
    }

    /**
     * 실패를 호출자에게 전파하지 않습니다.
     *
     * <p>{@code afterCommit}에서 던진 예외는 이미 Commit된 자금 처리를 되돌리지 못하면서 호출
     * 경로만 실패로 바꿉니다. 사용자에게는 "정산이 실패했다"로 보이지만 실제로는 정산이
     * 끝났습니다. 그 어긋남을 만들지 않습니다.</p>
     */
    private void recordSafely(NotificationRecordCommand command) {
        for (Long recipientUserId : command.getRecipientUserIds()) {
            try {
                recordTransaction.insertOne(command, recipientUserId);
            } catch (DuplicateKeyException alreadyRecorded) {
                // 같은 이벤트의 재처리다. 수신자가 둘인 유형에서 한쪽만 있어도 나머지는 계속 만든다.
                log.debug(
                        "이미 적재된 알림입니다. notiType={} sourceType={} sourceId={}",
                        command.getType(),
                        command.getType().sourceType(),
                        command.getSourceId());
            } catch (RuntimeException failure) {
                log.warn(
                        "알림 적재에 실패했습니다. 도메인 처리는 유지됩니다."
                                + " notiType={} sourceType={} sourceId={} workCaseId={}"
                                + " recipientUserId={}",
                        command.getType(),
                        command.getType().sourceType(),
                        command.getSourceId(),
                        command.getWorkCaseId(),
                        recipientUserId,
                        failure);
            }
        }
    }

    private static NotificationRecordCommand validate(NotificationRecordCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.getType(), "type");
        Objects.requireNonNull(command.getSourceId(), "sourceId");
        Objects.requireNonNull(command.getWorkCaseId(), "workCaseId");
        Objects.requireNonNull(command.getWorkCaseTitle(), "workCaseTitle");
        List<Long> recipients = command.getRecipientUserIds();
        if (recipients == null || recipients.isEmpty()
                || recipients.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("알림 수신자가 비어 있습니다.");
        }
        return command;
    }
}
