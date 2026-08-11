package com.gighub.attendance.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.gighub.attendance.domain.AttendanceFailureReason;
import com.gighub.attendance.domain.AttendanceScanOutcome;
import com.gighub.attendance.domain.AttendanceType;
import com.gighub.attendance.dto.AttendanceScanRequest;
import com.gighub.attendance.mapper.AttendanceRecordMapper;
import com.gighub.attendance.mapper.QrTokenMapper;
import com.gighub.attendance.mapper.param.AttendanceRecordInsertParam;
import com.gighub.attendance.mapper.result.AttendanceScanCandidateRow;
import com.gighub.attendance.mapper.result.AttendanceSuccessTimestampsRow;
import com.gighub.attendance.mapper.result.QrTokenRow;
import com.gighub.attendance.qr.QrHmacKeys;
import com.gighub.attendance.qr.QrTokenCodec;
import com.gighub.attendance.qr.QrTokenPayload;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.member.domain.UserRole;
import com.gighub.settlement.service.SettlementReservationService;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.service.WorkLifecycleCommandService;
import com.gighub.work.service.result.WorkLifecycleSnapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 스캔 판정의 주요 성공·거절 경로를 검증합니다. */
@ExtendWith(MockitoExtension.class)
class AttendanceScanExecutorTest {

    private static final long WORK_CASE_ID = 11L;
    private static final long WORKPLACE_ID = 3L;
    private static final long WORKER_ID = 5L;
    private static final long QR_TOKEN_ID = 9L;
    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 8, 11, 9, 0);
    private static final LocalDateTime ENDS_AT = LocalDateTime.of(2026, 8, 11, 18, 0);
    private static final byte[] NONCE = {
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
    };
    private static final byte[] HMAC_KEY =
            "01234567890123456789012345678901".getBytes(StandardCharsets.US_ASCII);

    private final AuthPrincipal principal = new AuthPrincipal(WORKER_ID, UserRole.WORKER, "근로자");

    @Mock
    private QrTokenMapper qrTokenMapper;

    @Mock
    private AttendanceRecordMapper attendanceRecordMapper;

    @Mock
    private WorkLifecycleCommandService workLifecycleCommandService;

    @Mock
    private SettlementReservationService settlementReservationService;

    @Mock
    private AttendanceScanAuditor scanAuditor;

    @Test
    void firstScanRecordsCheckInAndStartsTheWork() {
        LocalDateTime now = STARTS_AT.plusMinutes(5);
        givenCandidate(now, WorkCaseStatus.READY);
        givenAttendance(null, null);
        givenLock(WorkCaseStatus.READY);
        when(workLifecycleCommandService.transition(
                WORK_CASE_ID, WorkCaseStatus.READY, WorkCaseStatus.IN_PROGRESS))
                .thenReturn(true);

        AttendanceScanOutcome outcome = executor().execute(principal, onSite(), payload(), now);

        assertFalse(outcome.isRejected());
        assertEquals(AttendanceType.CHECK_IN, outcome.getScanType());
        assertEquals(now, outcome.getRecordedAt());
        // 출근은 정산 지급 시각을 예약하지 않습니다.
        verify(settlementReservationService, never()).scheduleDueAt(anyLong(), any());
    }

    @Test
    void checkOutAfterScheduledEndCompletesWorkAndSchedulesSettlementOnly() {
        LocalDateTime now = ENDS_AT.plusMinutes(10);
        givenCandidate(now, WorkCaseStatus.IN_PROGRESS);
        givenAttendance(STARTS_AT.plusMinutes(1), null);
        givenLock(WorkCaseStatus.IN_PROGRESS);
        when(workLifecycleCommandService.transition(
                WORK_CASE_ID, WorkCaseStatus.IN_PROGRESS, WorkCaseStatus.COMPLETED))
                .thenReturn(true);

        AttendanceScanOutcome outcome = executor().execute(principal, onSite(), payload(), now);

        assertFalse(outcome.isRejected());
        assertEquals(AttendanceType.CHECK_OUT, outcome.getScanType());
        // 정시 퇴근이므로 조기 퇴근 확인 시각은 남지 않습니다.
        assertEquals(null, outcome.getEarlyCheckoutConfirmedAt());
        verify(settlementReservationService).scheduleDueAt(WORK_CASE_ID, now);
    }

    @Test
    void earlyCheckOutAsksForConfirmationBeforeRecordingAnything() {
        LocalDateTime now = ENDS_AT.minusHours(1);
        givenCandidate(now, WorkCaseStatus.IN_PROGRESS);
        givenAttendance(STARTS_AT.plusMinutes(1), null);

        AttendanceScanOutcome outcome = executor().execute(principal, onSite(), payload(), now);

        assertTrue(outcome.isConfirmationRequired());
        assertEquals(ENDS_AT, outcome.getScheduledEndAt());
        verify(attendanceRecordMapper, never()).insertAttempt(any());
        verify(workLifecycleCommandService, never()).lock(anyLong());
    }

    @Test
    void confirmedEarlyCheckOutRecordsTheConfirmationTime() {
        LocalDateTime now = ENDS_AT.minusHours(1);
        givenCandidate(now, WorkCaseStatus.IN_PROGRESS);
        givenAttendance(STARTS_AT.plusMinutes(1), null);
        givenLock(WorkCaseStatus.IN_PROGRESS);
        when(workLifecycleCommandService.transition(
                WORK_CASE_ID, WorkCaseStatus.IN_PROGRESS, WorkCaseStatus.COMPLETED))
                .thenReturn(true);

        AttendanceScanOutcome outcome = executor().execute(
                principal,
                new AttendanceScanRequest("token", 37.5665, 126.9780, true),
                payload(),
                now);

        assertFalse(outcome.isRejected());
        assertEquals(now, outcome.getEarlyCheckoutConfirmedAt());

        ArgumentCaptor<AttendanceRecordInsertParam> saved =
                ArgumentCaptor.forClass(AttendanceRecordInsertParam.class);
        verify(attendanceRecordMapper).insertAttempt(saved.capture());
        assertEquals(now, saved.getValue().getEarlyCheckoutConfirmedAt());
    }

    /** 응답을 못 받은 client가 같은 출근 의도로 다시 스캔해도 퇴근으로 바뀌지 않아야 합니다. */
    @Test
    void checkInRetryReplaysTheCheckInInsteadOfCheckingOut() {
        LocalDateTime checkedInAt = STARTS_AT.plusMinutes(5);
        LocalDateTime now = checkedInAt.plusMinutes(5);
        givenCandidate(now, WorkCaseStatus.IN_PROGRESS);
        givenAttendance(checkedInAt, null);

        AttendanceScanOutcome outcome = executor().execute(principal, onSite(), payload(), now);

        assertFalse(outcome.isRejected());
        assertEquals(AttendanceType.CHECK_IN, outcome.getScanType());
        assertEquals(checkedInAt, outcome.getRecordedAt());
        verify(attendanceRecordMapper, never()).insertAttempt(any());
        verify(workLifecycleCommandService, never()).lock(anyLong());
    }

    @Test
    void scanOutsideAllowedRadiusIsRejectedAndAudited() {
        LocalDateTime now = STARTS_AT.plusMinutes(5);
        givenCandidate(now, WorkCaseStatus.READY);
        givenAttendance(null, null);

        AttendanceScanOutcome outcome = executor().execute(
                principal,
                new AttendanceScanRequest("token", 37.6000, 126.9780, null),
                payload(),
                now);

        assertTrue(outcome.isRejected());
        assertEquals(AttendanceFailureReason.DISTANCE_EXCEEDED, outcome.getFailureReason());
        // 성공 근태 행과 상태 전이는 만들지 않고, 거절 감사만 남깁니다.
        verify(attendanceRecordMapper, never()).insertAttempt(any());
        verify(workLifecycleCommandService, never()).lock(anyLong());

        ArgumentCaptor<BigDecimal> distance = ArgumentCaptor.forClass(BigDecimal.class);
        verify(scanAuditor).recordRejection(
                org.mockito.ArgumentMatchers.eq(WORK_CASE_ID),
                org.mockito.ArgumentMatchers.eq(WORKER_ID),
                org.mockito.ArgumentMatchers.eq(QR_TOKEN_ID),
                org.mockito.ArgumentMatchers.eq(AttendanceType.CHECK_IN),
                org.mockito.ArgumentMatchers.eq(AttendanceFailureReason.DISTANCE_EXCEEDED),
                distance.capture(),
                org.mockito.ArgumentMatchers.eq(now));
        assertNotNull(distance.getValue());
        assertTrue(distance.getValue().doubleValue() > 100);
    }

    private AttendanceScanExecutor executor() {
        return new AttendanceScanExecutor(
                qrTokenMapper,
                attendanceRecordMapper,
                workLifecycleCommandService,
                settlementReservationService,
                scanAuditor);
    }

    /** 사업장 좌표와 같은 지점이라 거리 판정을 항상 통과합니다. */
    private static AttendanceScanRequest onSite() {
        return new AttendanceScanRequest("token", 37.5665, 126.9780, null);
    }

    /** 생성자가 package-private이라 실제 서명·검증을 거쳐 값을 얻습니다. */
    private static QrTokenPayload payload() {
        QrTokenCodec codec = new QrTokenCodec(new QrHmacKeys("k1", Map.of("k1", HMAC_KEY)));
        return codec.verify(codec.sign(WORKPLACE_ID, NONCE)).orElseThrow();
    }

    private void givenCandidate(LocalDateTime now, WorkCaseStatus status) {
        when(qrTokenMapper.findActiveByWorkplaceId(WORKPLACE_ID)).thenReturn(activeQr());
        when(attendanceRecordMapper.findScanCandidates(
                org.mockito.ArgumentMatchers.eq(WORKER_ID),
                org.mockito.ArgumentMatchers.eq(WORKPLACE_ID),
                any(),
                any()))
                .thenReturn(List.of(candidate(status)));
    }

    private void givenAttendance(LocalDateTime checkedInAt, LocalDateTime checkedOutAt) {
        when(attendanceRecordMapper.findSuccessTimestamps(WORK_CASE_ID))
                .thenReturn(AttendanceSuccessTimestampsRow.builder()
                        .checkedInAt(checkedInAt)
                        .checkedOutAt(checkedOutAt)
                        .build());
    }

    private void givenLock(WorkCaseStatus status) {
        when(workLifecycleCommandService.lock(WORK_CASE_ID))
                .thenReturn(new WorkLifecycleSnapshot(WORK_CASE_ID, status, STARTS_AT, ENDS_AT));
        when(attendanceRecordMapper.insertAttempt(any())).thenReturn(1);
    }

    private static QrTokenRow activeQr() {
        QrTokenRow row = new QrTokenRow();
        row.setId(QR_TOKEN_ID);
        row.setWorkplaceId(WORKPLACE_ID);
        row.setTokenNonce(NONCE);
        return row;
    }

    private static AttendanceScanCandidateRow candidate(WorkCaseStatus status) {
        return AttendanceScanCandidateRow.builder()
                .workCaseId(WORK_CASE_ID)
                .status(status)
                .startsAt(STARTS_AT)
                .endsAt(ENDS_AT)
                .workplaceLatitude(BigDecimal.valueOf(37.5665))
                .workplaceLongitude(BigDecimal.valueOf(126.9780))
                .allowedRadiusMeters(BigDecimal.valueOf(100))
                .build();
    }
}
