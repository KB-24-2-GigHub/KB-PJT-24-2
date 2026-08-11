package com.gighub.attendance.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import com.gighub.attendance.domain.AttendanceFailureReason;
import com.gighub.attendance.domain.AttendanceResult;
import com.gighub.attendance.domain.AttendanceScanOutcome;
import com.gighub.attendance.domain.AttendanceType;
import com.gighub.attendance.dto.AttendanceScanRequest;
import com.gighub.attendance.mapper.AttendanceRecordMapper;
import com.gighub.attendance.mapper.QrTokenMapper;
import com.gighub.attendance.mapper.param.AttendanceRecordInsertParam;
import com.gighub.attendance.mapper.result.AttendanceScanCandidateRow;
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
import com.gighub.workplace.service.WorkplaceOwnershipService;
import com.gighub.workplace.service.result.WorkplaceLocationSnapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
    private static final BigDecimal SITE_LATITUDE = new BigDecimal("37.5665000");
    private static final BigDecimal SITE_LONGITUDE = new BigDecimal("126.9780000");
    private static final byte[] NONCE = {
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
    };
    private static final byte[] HMAC_KEY =
            "01234567890123456789012345678901".getBytes(StandardCharsets.US_ASCII);

    private final AuthPrincipal principal = new AuthPrincipal(WORKER_ID, UserRole.WORKER, "근로자");

    @Mock
    private WorkplaceOwnershipService workplaceOwnershipService;

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
        givenActiveWorkplaceAndQr();
        givenCandidate(AttendanceType.CHECK_IN);
        givenLock(WorkCaseStatus.READY);
        when(workLifecycleCommandService.transition(
                WORK_CASE_ID, WorkCaseStatus.READY, WorkCaseStatus.IN_PROGRESS))
                .thenReturn(true);

        AttendanceScanOutcome outcome = executor().execute(principal, onSite(now), payload(), now);

        assertFalse(outcome.isRejected());
        assertEquals(AttendanceType.CHECK_IN, outcome.getScanType());
        assertEquals(now, outcome.getRecordedAt());
        // 5분 지각은 파생값으로만 나오고 정산 예약은 하지 않습니다.
        assertTrue(outcome.isLate());
        assertEquals(5, outcome.getLateMinutes());
        assertNull(outcome.getSettlementDueAt());
        verify(settlementReservationService, never()).schedulePayout(anyLong(), any());
    }

    @Test
    void checkOutAfterScheduledEndCompletesWorkAndSchedulesPayoutIn24Hours() {
        LocalDateTime now = ENDS_AT.plusMinutes(10);
        givenActiveWorkplaceAndQr();
        givenCandidate(AttendanceType.CHECK_OUT);
        givenLock(WorkCaseStatus.IN_PROGRESS);
        when(workLifecycleCommandService.transition(
                WORK_CASE_ID, WorkCaseStatus.IN_PROGRESS, WorkCaseStatus.COMPLETED))
                .thenReturn(true);

        AttendanceScanOutcome outcome = executor().execute(principal, onSite(now), payload(), now);

        assertFalse(outcome.isRejected());
        assertEquals(AttendanceType.CHECK_OUT, outcome.getScanType());
        // 정시 퇴근이라 조기 확인 시각은 남지 않습니다.
        assertNull(outcome.getEarlyCheckoutConfirmedAt());
        assertEquals(now.plusHours(24), outcome.getSettlementDueAt());
        verify(settlementReservationService).schedulePayout(WORK_CASE_ID, now.plusHours(24));
    }

    @Test
    void earlyCheckOutAsksForConfirmationBeforeRecordingAnything() {
        LocalDateTime now = ENDS_AT.minusHours(1);
        givenActiveWorkplaceAndQr();
        givenCandidate(AttendanceType.CHECK_OUT);

        AttendanceScanOutcome outcome = executor().execute(principal, onSite(now), payload(), now);

        assertTrue(outcome.isConfirmationRequired());
        assertEquals(ENDS_AT, outcome.getScheduledEndAt());
        verify(attendanceRecordMapper, never()).insertAttempt(any());
        verify(workLifecycleCommandService, never()).lock(anyLong());
    }

    @Test
    void confirmedEarlyCheckOutRecordsTheConfirmationTime() {
        LocalDateTime now = ENDS_AT.minusHours(1);
        givenActiveWorkplaceAndQr();
        givenCandidate(AttendanceType.CHECK_OUT);
        givenLock(WorkCaseStatus.IN_PROGRESS);
        when(workLifecycleCommandService.transition(
                WORK_CASE_ID, WorkCaseStatus.IN_PROGRESS, WorkCaseStatus.COMPLETED))
                .thenReturn(true);

        AttendanceScanOutcome outcome = executor().execute(
                principal, request(now, SITE_LATITUDE, SITE_LONGITUDE, true), payload(), now);

        assertFalse(outcome.isRejected());
        assertEquals(now, outcome.getEarlyCheckoutConfirmedAt());

        ArgumentCaptor<AttendanceRecordInsertParam> saved =
                ArgumentCaptor.forClass(AttendanceRecordInsertParam.class);
        verify(attendanceRecordMapper).insertAttempt(saved.capture());
        assertEquals(now, saved.getValue().getEarlyCheckoutConfirmedAt());
        assertEquals(AttendanceResult.SUCCESS, saved.getValue().getResult());
    }

    @Test
    void scanOutsideAllowedRadiusIsRejectedAndAudited() {
        LocalDateTime now = STARTS_AT.plusMinutes(5);
        givenActiveWorkplaceAndQr();
        givenCandidate(AttendanceType.CHECK_IN);

        // 사업장에서 약 1.1km 떨어진 좌표입니다.
        AttendanceScanOutcome outcome = executor().execute(
                principal,
                request(now, new BigDecimal("37.5765000"), SITE_LONGITUDE, false),
                payload(),
                now);

        assertTrue(outcome.isRejected());
        assertEquals(AttendanceFailureReason.OUTSIDE_RADIUS, outcome.getFailureReason());
        // 성공 근태 행과 상태 전이는 만들지 않고 거절 감사만 남깁니다.
        verify(attendanceRecordMapper, never()).insertAttempt(any());
        verify(workLifecycleCommandService, never()).lock(anyLong());

        ArgumentCaptor<BigDecimal> distance = ArgumentCaptor.forClass(BigDecimal.class);
        verify(scanAuditor).recordRejection(
                eq(WORK_CASE_ID),
                eq(WORKER_ID),
                eq(QR_TOKEN_ID),
                eq(AttendanceType.CHECK_IN),
                eq(AttendanceFailureReason.OUTSIDE_RADIUS),
                distance.capture(),
                any(),
                any(),
                eq(now));
        assertTrue(distance.getValue().doubleValue() > 100);
    }

    private AttendanceScanExecutor executor() {
        return new AttendanceScanExecutor(
                workplaceOwnershipService,
                qrTokenMapper,
                attendanceRecordMapper,
                workLifecycleCommandService,
                settlementReservationService,
                scanAuditor);
    }

    /** 사업장 좌표와 같은 지점이라 거리 판정을 항상 통과합니다. */
    private static AttendanceScanRequest onSite(LocalDateTime now) {
        return request(now, SITE_LATITUDE, SITE_LONGITUDE, false);
    }

    private static AttendanceScanRequest request(
            LocalDateTime now, BigDecimal latitude, BigDecimal longitude, boolean confirm) {
        Instant capturedAt = now.atZone(ZoneId.of("Asia/Seoul")).toInstant();
        return new AttendanceScanRequest(
                "token", latitude, longitude, new BigDecimal("10.00"), capturedAt, confirm);
    }

    /** 생성자가 package-private이라 실제 서명·검증을 거쳐 값을 얻습니다. */
    private static QrTokenPayload payload() {
        QrTokenCodec codec = new QrTokenCodec(new QrHmacKeys("k1", Map.of("k1", HMAC_KEY)));
        return codec.verify(codec.sign(WORKPLACE_ID, NONCE)).orElseThrow();
    }

    private void givenActiveWorkplaceAndQr() {
        when(workplaceOwnershipService.lockActiveWorkplaceLocation(WORKPLACE_ID))
                .thenReturn(new WorkplaceLocationSnapshot(
                        WORKPLACE_ID, SITE_LATITUDE, SITE_LONGITUDE));
        when(qrTokenMapper.findActiveByWorkplaceIdForUpdate(WORKPLACE_ID)).thenReturn(activeQr());
    }

    private void givenCandidate(AttendanceType scanType) {
        when(attendanceRecordMapper.findActiveScanCandidates(
                eq(WORKER_ID), eq(WORKPLACE_ID), any(), any(), any()))
                .thenReturn(List.of(candidate(scanType)));
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

    private static AttendanceScanCandidateRow candidate(AttendanceType scanType) {
        return AttendanceScanCandidateRow.builder()
                .workCaseId(WORK_CASE_ID)
                .status(scanType == AttendanceType.CHECK_IN
                        ? WorkCaseStatus.READY
                        : WorkCaseStatus.IN_PROGRESS)
                .startsAt(STARTS_AT)
                .endsAt(ENDS_AT)
                .scanType(scanType)
                .build();
    }
}
