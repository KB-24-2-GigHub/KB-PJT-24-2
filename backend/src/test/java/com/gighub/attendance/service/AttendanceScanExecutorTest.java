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
import com.gighub.attendance.service.result.AttendanceScanOutcome;
import com.gighub.attendance.domain.AttendanceType;
import com.gighub.attendance.dto.AttendanceScanConfirmationResponse;
import com.gighub.attendance.dto.AttendanceScanRequest;
import com.gighub.attendance.dto.AttendanceScanResponse;
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
import com.gighub.idempotency.IdempotencyClaimService;
import com.gighub.member.domain.UserRole;
import com.gighub.settlement.service.SettlementReservationService;
import com.gighub.settlement.service.command.SettlementCalculationCommand;
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
    private static final long CLAIM_ID = 77L;
    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 8, 11, 9, 0);
    private static final LocalDateTime ENDS_AT = LocalDateTime.of(2026, 8, 11, 18, 0);
    private static final BigDecimal SITE_LATITUDE = new BigDecimal("37.5665000");
    private static final BigDecimal SITE_LONGITUDE = new BigDecimal("126.9780000");
    private static final BigDecimal DEFAULT_RADIUS_METERS = new BigDecimal("100.00");
    private static final BigDecimal DEMO_RADIUS_METERS = new BigDecimal("999999.00");
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

    @Mock
    private IdempotencyClaimService claimService;

    private final AttendanceScanReplayCodec replayCodec = new AttendanceScanReplayCodec();

    @Test
    void firstScanRecordsCheckInAndStartsTheWork() {
        Instant receivedAt = toInstant(STARTS_AT.plusMinutes(5));
        givenActiveWorkplaceAndQr();
        givenCandidate(AttendanceType.CHECK_IN);
        givenLock(WorkCaseStatus.READY);
        when(workLifecycleCommandService.transition(
                WORK_CASE_ID, WorkCaseStatus.READY, WorkCaseStatus.IN_PROGRESS))
                .thenReturn(true);

        AttendanceScanOutcome outcome = executor().execute(
                principal, onSite(receivedAt), payload(), CLAIM_ID, receivedAt);

        assertFalse(outcome.isRejected());
        AttendanceScanResponse response = (AttendanceScanResponse) outcome.getResponse();
        assertEquals(AttendanceType.CHECK_IN, response.getScanType());
        // 5분 지각은 파생값으로만 나오고 정산 예약은 하지 않습니다.
        assertTrue(response.getIsLate());
        assertEquals(5, response.getLateMinutes());
        assertNull(response.getSettlementDueAt());
        verify(settlementReservationService, never()).schedulePayout(any(), any());
        // 멱등 Claim은 근태 판정과 같은 Transaction에서 완료되어야 합니다.
        verify(claimService).complete(eq(CLAIM_ID), eq(200), any());
    }

    @Test
    void checkOutAfterScheduledEndCompletesWorkAndSchedulesPayoutIn24Hours() {
        Instant receivedAt = toInstant(ENDS_AT.plusMinutes(10));
        givenActiveWorkplaceAndQr();
        givenCandidate(AttendanceType.CHECK_OUT);
        givenLock(WorkCaseStatus.IN_PROGRESS);
        when(workLifecycleCommandService.transition(
                WORK_CASE_ID, WorkCaseStatus.IN_PROGRESS, WorkCaseStatus.COMPLETED))
                .thenReturn(true);
        givenAttendanceTimes(STARTS_AT, ENDS_AT.plusMinutes(10));

        AttendanceScanOutcome outcome = executor().execute(
                principal, onSite(receivedAt), payload(), CLAIM_ID, receivedAt);

        assertFalse(outcome.isRejected());
        AttendanceScanResponse response = (AttendanceScanResponse) outcome.getResponse();
        assertEquals(AttendanceType.CHECK_OUT, response.getScanType());
        // 정시 퇴근이라 조기 확인 시각은 남지 않습니다.
        assertNull(response.getEarlyCheckoutConfirmedAt());
        assertEquals(ENDS_AT.plusMinutes(10).plusHours(24), toLocalDateTime(response.getSettlementDueAt()));
        verify(settlementReservationService).schedulePayout(
                any(SettlementCalculationCommand.class),
                eq(ENDS_AT.plusMinutes(10).plusHours(24)));
        verify(claimService).complete(eq(CLAIM_ID), eq(200), any());
    }

    @Test
    void earlyCheckOutAsksForConfirmationBeforeRecordingAnything() {
        Instant receivedAt = toInstant(ENDS_AT.minusHours(1));
        givenActiveWorkplaceAndQr();
        givenCandidate(AttendanceType.CHECK_OUT);

        AttendanceScanOutcome outcome = executor().execute(
                principal, onSite(receivedAt), payload(), CLAIM_ID, receivedAt);

        assertFalse(outcome.isRejected());
        AttendanceScanConfirmationResponse response =
                (AttendanceScanConfirmationResponse) outcome.getResponse();
        assertEquals("CONFIRMATION_REQUIRED", response.getResult());
        assertEquals(ENDS_AT, toLocalDateTime(response.getScheduledEndAt()));
        verify(attendanceRecordMapper, never()).insertAttempt(any());
        verify(workLifecycleCommandService, never()).lock(anyLong());
        // 확인 요청도 24시간 Replay 대상이라 Claim을 완료합니다.
        verify(claimService).complete(eq(CLAIM_ID), eq(200), any());
    }

    @Test
    void confirmedEarlyCheckOutRecordsTheConfirmationTime() {
        Instant receivedAt = toInstant(ENDS_AT.minusHours(1));
        givenActiveWorkplaceAndQr();
        givenCandidate(AttendanceType.CHECK_OUT);
        givenLock(WorkCaseStatus.IN_PROGRESS);
        when(workLifecycleCommandService.transition(
                WORK_CASE_ID, WorkCaseStatus.IN_PROGRESS, WorkCaseStatus.COMPLETED))
                .thenReturn(true);
        givenAttendanceTimes(STARTS_AT, ENDS_AT.minusHours(1));

        AttendanceScanOutcome outcome = executor().execute(
                principal,
                request(receivedAt, SITE_LATITUDE, SITE_LONGITUDE, true),
                payload(),
                CLAIM_ID,
                receivedAt);

        assertFalse(outcome.isRejected());
        AttendanceScanResponse response = (AttendanceScanResponse) outcome.getResponse();
        assertEquals(ENDS_AT.minusHours(1), toLocalDateTime(response.getEarlyCheckoutConfirmedAt()));

        ArgumentCaptor<AttendanceRecordInsertParam> saved =
                ArgumentCaptor.forClass(AttendanceRecordInsertParam.class);
        verify(attendanceRecordMapper).insertAttempt(saved.capture());
        assertEquals(ENDS_AT.minusHours(1), saved.getValue().getEarlyCheckoutConfirmedAt());
        assertEquals(AttendanceResult.SUCCESS, saved.getValue().getResult());
    }

    @Test
    void scanOutsideAllowedRadiusIsRejectedAndAudited() {
        Instant receivedAt = toInstant(STARTS_AT.plusMinutes(5));
        givenActiveWorkplaceAndQr();
        givenCandidate(AttendanceType.CHECK_IN);

        // 사업장에서 약 1.1km 떨어진 좌표입니다.
        AttendanceScanOutcome outcome = executor().execute(
                principal,
                request(receivedAt, new BigDecimal("37.5765000"), SITE_LONGITUDE, false),
                payload(),
                CLAIM_ID,
                receivedAt);

        assertTrue(outcome.isRejected());
        assertEquals(AttendanceFailureReason.OUTSIDE_RADIUS, outcome.getFailureReason());
        // 성공 근태 행과 상태 전이는 만들지 않고 거절 감사만 남깁니다. 거절은 저장·재생
        // 대상이 아니므로 Claim을 완료하지 않습니다.
        verify(attendanceRecordMapper, never()).insertAttempt(any());
        verify(workLifecycleCommandService, never()).lock(anyLong());
        verify(claimService, never()).complete(anyLong(), any(Integer.class), any());

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
                any());
        assertTrue(distance.getValue().doubleValue() > 100);
    }

    @Test
    void demoWorkplaceUsesItsExpandedStoredRadius() {
        Instant receivedAt = toInstant(STARTS_AT.plusMinutes(5));
        givenActiveWorkplaceAndQr(DEMO_RADIUS_METERS);
        givenCandidate(AttendanceType.CHECK_IN);
        givenLock(WorkCaseStatus.READY);
        when(workLifecycleCommandService.transition(
                WORK_CASE_ID, WorkCaseStatus.READY, WorkCaseStatus.IN_PROGRESS))
                .thenReturn(true);

        // 일반 100m 사업장에서는 거절되는 약 1.1km 거리도 시연 Seed 사업장은 허용합니다.
        AttendanceScanOutcome outcome = executor().execute(
                principal,
                request(receivedAt, new BigDecimal("37.5765000"), SITE_LONGITUDE, false),
                payload(),
                CLAIM_ID,
                receivedAt);

        assertFalse(outcome.isRejected());
        verify(workLifecycleCommandService).transition(
                WORK_CASE_ID, WorkCaseStatus.READY, WorkCaseStatus.IN_PROGRESS);
    }

    @Test
    void staleCaptureIsRejectedAfterCandidateIsResolvedSoItCanBeAudited() {
        Instant receivedAt = toInstant(STARTS_AT.plusMinutes(5));
        givenActiveWorkplaceAndQr();
        givenCandidate(AttendanceType.CHECK_IN);

        // capturedAt이 수신 시각보다 10분 앞서 승인된 5분 신선도 범위를 벗어납니다.
        AttendanceScanOutcome outcome = executor().execute(
                principal,
                new AttendanceScanRequest(
                        "token",
                        SITE_LATITUDE,
                        SITE_LONGITUDE,
                        new BigDecimal("10.00"),
                        receivedAt.minusSeconds(600),
                        false),
                payload(),
                CLAIM_ID,
                receivedAt);

        assertTrue(outcome.isRejected());
        assertEquals(AttendanceFailureReason.LOCATION_STALE, outcome.getFailureReason());
        // 근무가 특정된 뒤의 거절이므로 근태 감사 행으로 남아야 합니다.
        verify(scanAuditor).recordRejection(
                eq(WORK_CASE_ID),
                eq(WORKER_ID),
                eq(QR_TOKEN_ID),
                eq(AttendanceType.CHECK_IN),
                eq(AttendanceFailureReason.LOCATION_STALE),
                any(),
                any(),
                any(),
                any());
    }

    @Test
    void checkInAtShortWorkEndBoundaryIsRejectedAfterRowLock() {
        LocalDateTime shortEndsAt = STARTS_AT.plusMinutes(30);
        Instant receivedAt = toInstant(shortEndsAt);
        givenActiveWorkplaceAndQr();
        givenCandidate(AttendanceType.CHECK_IN, STARTS_AT, shortEndsAt);
        givenLock(WorkCaseStatus.READY, STARTS_AT, shortEndsAt);

        AttendanceScanOutcome outcome = executor().execute(
                principal, onSite(receivedAt), payload(), CLAIM_ID, receivedAt);

        assertTrue(outcome.isRejected());
        assertEquals(AttendanceFailureReason.TIME_WINDOW_CLOSED, outcome.getFailureReason());
        verify(attendanceRecordMapper, never()).insertAttempt(any());
        verify(workLifecycleCommandService, never()).transition(
                WORK_CASE_ID, WorkCaseStatus.READY, WorkCaseStatus.IN_PROGRESS);
    }

    @Test
    void settlementScheduleFailureRollsBackTheWholeCheckOut() {
        Instant receivedAt = toInstant(ENDS_AT.plusMinutes(10));
        givenActiveWorkplaceAndQr();
        givenCandidate(AttendanceType.CHECK_OUT);
        givenLock(WorkCaseStatus.IN_PROGRESS);
        when(workLifecycleCommandService.transition(
                WORK_CASE_ID, WorkCaseStatus.IN_PROGRESS, WorkCaseStatus.COMPLETED))
                .thenReturn(true);
        givenAttendanceTimes(STARTS_AT, ENDS_AT.plusMinutes(10));
        // 정산 행이 없거나 WAITING이 아닌 이상 상태를 흉내 냅니다.
        org.mockito.Mockito.doThrow(new IllegalStateException("정산 지급 예약을 반영하지 못했습니다."))
                .when(settlementReservationService).schedulePayout(any(), any());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> executor().execute(
                principal, onSite(receivedAt), payload(), CLAIM_ID, receivedAt));

        // 근태 상태 전이가 이미 실행됐더라도, 이 메서드가 던진 예외로 Transaction 전체가
        // 되돌아가야 하며 Claim도 완료 호출까지 가지 않아야 합니다.
        verify(claimService, never()).complete(anyLong(), any(Integer.class), any());
    }

    private AttendanceScanExecutor executor() {
        return new AttendanceScanExecutor(
                workplaceOwnershipService,
                qrTokenMapper,
                attendanceRecordMapper,
                workLifecycleCommandService,
                settlementReservationService,
                scanAuditor,
                replayCodec,
                claimService);
    }

    /** 사업장 좌표와 같은 지점이라 거리 판정을 항상 통과합니다. */
    private static AttendanceScanRequest onSite(Instant receivedAt) {
        return request(receivedAt, SITE_LATITUDE, SITE_LONGITUDE, false);
    }

    private static AttendanceScanRequest request(
            Instant receivedAt, BigDecimal latitude, BigDecimal longitude, boolean confirm) {
        return new AttendanceScanRequest(
                "token", latitude, longitude, new BigDecimal("10.00"), receivedAt, confirm);
    }

    /** 생성자가 package-private이라 실제 서명·검증을 거쳐 값을 얻습니다. */
    private static QrTokenPayload payload() {
        QrTokenCodec codec = new QrTokenCodec(new QrHmacKeys("k1", Map.of("k1", HMAC_KEY)));
        return codec.verify(codec.sign(WORKPLACE_ID, NONCE)).orElseThrow();
    }

    private void givenActiveWorkplaceAndQr() {
        givenActiveWorkplaceAndQr(DEFAULT_RADIUS_METERS);
    }

    private void givenActiveWorkplaceAndQr(BigDecimal radiusMeters) {
        when(workplaceOwnershipService.lockActiveWorkplaceLocation(WORKPLACE_ID))
                .thenReturn(new WorkplaceLocationSnapshot(
                        WORKPLACE_ID, SITE_LATITUDE, SITE_LONGITUDE, radiusMeters));
        when(qrTokenMapper.findActiveByWorkplaceIdForUpdate(WORKPLACE_ID)).thenReturn(activeQr());
    }

    private void givenCandidate(AttendanceType scanType) {
        givenCandidate(scanType, STARTS_AT, ENDS_AT);
    }

    private void givenCandidate(
            AttendanceType scanType,
            LocalDateTime startsAt,
            LocalDateTime endsAt) {
        when(attendanceRecordMapper.findActiveScanCandidates(
                eq(WORKER_ID), eq(WORKPLACE_ID), any(), any(), any()))
                .thenReturn(List.of(candidate(scanType, startsAt, endsAt)));
    }

    private void givenLock(WorkCaseStatus status) {
        givenLock(status, STARTS_AT, ENDS_AT);
        when(attendanceRecordMapper.insertAttempt(any())).thenReturn(1);
    }

    private void givenLock(
            WorkCaseStatus status,
            LocalDateTime startsAt,
            LocalDateTime endsAt) {
        when(workLifecycleCommandService.lock(WORK_CASE_ID))
                .thenReturn(new WorkLifecycleSnapshot(
                        WORK_CASE_ID, status, startsAt, endsAt, 100_000L, 0, false));
    }

    private void givenAttendanceTimes(
            LocalDateTime checkedInAt, LocalDateTime checkedOutAt) {
        when(attendanceRecordMapper.findSuccessTimestamps(WORK_CASE_ID))
                .thenReturn(AttendanceSuccessTimestampsRow.builder()
                        .checkedInAt(checkedInAt)
                        .checkedOutAt(checkedOutAt)
                        .build());
    }

    private static QrTokenRow activeQr() {
        QrTokenRow row = new QrTokenRow();
        row.setId(QR_TOKEN_ID);
        row.setWorkplaceId(WORKPLACE_ID);
        row.setTokenNonce(NONCE);
        return row;
    }

    private static AttendanceScanCandidateRow candidate(AttendanceType scanType) {
        return candidate(scanType, STARTS_AT, ENDS_AT);
    }

    private static AttendanceScanCandidateRow candidate(
            AttendanceType scanType,
            LocalDateTime startsAt,
            LocalDateTime endsAt) {
        return AttendanceScanCandidateRow.builder()
                .workCaseId(WORK_CASE_ID)
                .status(scanType == AttendanceType.CHECK_IN
                        ? WorkCaseStatus.READY
                        : WorkCaseStatus.IN_PROGRESS)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .scanType(scanType)
                .build();
    }

    private static Instant toInstant(LocalDateTime value) {
        return value.atZone(ZoneId.of("Asia/Seoul")).toInstant();
    }

    private static LocalDateTime toLocalDateTime(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneId.of("Asia/Seoul"));
    }
}
