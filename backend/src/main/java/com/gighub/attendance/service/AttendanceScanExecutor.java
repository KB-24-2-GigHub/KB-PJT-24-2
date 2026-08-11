package com.gighub.attendance.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import com.gighub.attendance.domain.AttendanceFailureReason;
import com.gighub.attendance.domain.AttendanceResult;
import com.gighub.attendance.domain.AttendanceScanOutcome;
import com.gighub.attendance.domain.AttendanceType;
import com.gighub.attendance.domain.AttendanceWindowPolicy;
import com.gighub.attendance.dto.AttendanceScanConfirmationResponse;
import com.gighub.attendance.dto.AttendanceScanRequest;
import com.gighub.attendance.dto.AttendanceScanResponse;
import com.gighub.attendance.dto.AttendanceScanResult;
import com.gighub.attendance.exception.AttendanceScanException;
import com.gighub.attendance.geo.HaversineDistanceCalculator;
import com.gighub.attendance.mapper.AttendanceRecordMapper;
import com.gighub.attendance.mapper.QrTokenMapper;
import com.gighub.attendance.mapper.param.AttendanceRecordInsertParam;
import com.gighub.attendance.mapper.result.AttendanceScanCandidateRow;
import com.gighub.attendance.mapper.result.QrTokenRow;
import com.gighub.attendance.qr.QrTokenPayload;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.api.ApiTimes;
import com.gighub.idempotency.IdempotencyClaimService;
import com.gighub.settlement.service.SettlementReservationService;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.service.WorkLifecycleCommandService;
import com.gighub.work.service.result.WorkLifecycleSnapshot;
import com.gighub.workplace.service.WorkplaceOwnershipService;
import com.gighub.workplace.service.result.WorkplaceLocationSnapshot;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스캔 한 건의 DB 판정을 하나의 Transaction으로 처리합니다.
 *
 * <p>승인된 잠금 순서는 {@code workplaces -> qr_tokens -> work_cases}입니다. QR 재발급도
 * {@code workplaces}를 먼저 잠그므로 두 흐름이 같은 순서로 줄을 서고, 폐기된 QR이 뒤늦게
 * 성공하지 않습니다.</p>
 *
 * <p>근무를 정한 뒤의 거절은 예외 대신 {@link AttendanceScanOutcome}으로 돌려줍니다. 거절
 * 감사 행이 같은 Transaction에서 commit 되어야 하므로, 여기서 예외를 던지면 남기려던
 * 기록이 함께 되돌아갑니다. 사용자 오류로 바꾸는 일은 Transaction 밖의 호출부가 합니다.</p>
 *
 * <p>성공(기록 또는 조기 퇴근 확인 요청)은 {@link IdempotencyClaimService#complete}를 이
 * Transaction의 마지막 DB 변경으로 호출합니다. Claim 완료는 {@code Propagation.MANDATORY}라
 * 호출자 Transaction에 참여해야만 하고, 본 처리와 다른 Transaction에서 부르면 Transaction이
 * 없다는 예외로 실패합니다.</p>
 *
 * <p>{@code work_cases}와 {@code settlements}는 각 모듈의 공개 명령으로만 바꿉니다. Wallet과
 * Escrow의 금액·원장은 이 Transaction에서 변경하지 않습니다.</p>
 */
@Service
public class AttendanceScanExecutor {

    /** ATT-003이 고정한 인증 반경입니다. 반올림 전 값이 이 값을 포함합니다. */
    private static final BigDecimal ALLOWED_RADIUS_METERS = BigDecimal.valueOf(100);

    /** 지급 예정 시각은 성공 판정 시각으로부터 이만큼 뒤입니다. */
    private static final int SETTLEMENT_DUE_HOURS = 24;

    private static final int DISTANCE_SCALE = 2;
    private static final int RESPONSE_HTTP_STATUS = 200;

    private final WorkplaceOwnershipService workplaceOwnershipService;
    private final QrTokenMapper qrTokenMapper;
    private final AttendanceRecordMapper attendanceRecordMapper;
    private final WorkLifecycleCommandService workLifecycleCommandService;
    private final SettlementReservationService settlementReservationService;
    private final AttendanceScanAuditor scanAuditor;
    private final AttendanceScanReplayCodec replayCodec;
    private final IdempotencyClaimService claimService;

    public AttendanceScanExecutor(
            WorkplaceOwnershipService workplaceOwnershipService,
            QrTokenMapper qrTokenMapper,
            AttendanceRecordMapper attendanceRecordMapper,
            WorkLifecycleCommandService workLifecycleCommandService,
            SettlementReservationService settlementReservationService,
            AttendanceScanAuditor scanAuditor,
            AttendanceScanReplayCodec replayCodec,
            IdempotencyClaimService claimService) {
        this.workplaceOwnershipService = workplaceOwnershipService;
        this.qrTokenMapper = qrTokenMapper;
        this.attendanceRecordMapper = attendanceRecordMapper;
        this.workLifecycleCommandService = workLifecycleCommandService;
        this.settlementReservationService = settlementReservationService;
        this.scanAuditor = scanAuditor;
        this.replayCodec = replayCodec;
        this.claimService = claimService;
    }

    @Transactional
    public AttendanceScanOutcome execute(
            AuthPrincipal principal,
            AttendanceScanRequest request,
            QrTokenPayload payload,
            long claimId,
            Instant receivedAt) {
        LocalDateTime attemptedAt = ApiTimes.toLocalDateTime(receivedAt);

        // 1단계: 사업장을 먼저 잠그고 거리 판정 기준이 될 현재 좌표를 읽습니다.
        WorkplaceLocationSnapshot workplace =
                workplaceOwnershipService.lockActiveWorkplaceLocation(payload.workplaceId());
        if (workplace == null) {
            scanAuditor.logUnresolvedRejection(principal.getUserId(), "WORKPLACE_INACTIVE");
            throw AttendanceScanException.qrRevoked();
        }

        // 2단계: 활성 QR을 잠그고 Token이 현재 QR인지 확인합니다.
        QrTokenRow activeQr = requireActiveQr(principal, payload);

        AttendanceScanCandidateRow candidate =
                requireSingleCandidate(principal, payload.workplaceId(), attemptedAt);

        // 근무와 유형을 정한 뒤에야 신선도 거부를 감사 행으로 남길 수 있습니다.
        if (!AttendanceWindowPolicy.isCaptureFresh(request.getCapturedAt(), receivedAt)) {
            return reject(
                    principal, candidate, activeQr, request, claimId,
                    AttendanceFailureReason.LOCATION_STALE, null, attemptedAt);
        }

        if (!workplace.hasCoordinates()) {
            return reject(
                    principal, candidate, activeQr, request, claimId,
                    AttendanceFailureReason.LOCATION_INACCURATE, null, attemptedAt);
        }

        BigDecimal distance = distanceMeters(workplace, request);
        if (distance.compareTo(ALLOWED_RADIUS_METERS) > 0) {
            return reject(
                    principal, candidate, activeQr, request, claimId,
                    AttendanceFailureReason.OUTSIDE_RADIUS, distance, attemptedAt);
        }

        return candidate.getScanType() == AttendanceType.CHECK_IN
                ? checkIn(principal, candidate, activeQr, request, claimId, distance, attemptedAt)
                : checkOut(principal, candidate, activeQr, request, claimId, distance, attemptedAt);
    }

    private AttendanceScanOutcome checkIn(
            AuthPrincipal principal,
            AttendanceScanCandidateRow candidate,
            QrTokenRow activeQr,
            AttendanceScanRequest request,
            long claimId,
            BigDecimal distance,
            LocalDateTime attemptedAt) {
        // 3단계: 근무 행을 잠그고 후보 조회 이후 상태가 바뀌지 않았는지 다시 확인합니다.
        WorkLifecycleSnapshot lock = workLifecycleCommandService.lock(candidate.getWorkCaseId());
        if (lock == null || lock.status() != WorkCaseStatus.READY) {
            return reject(
                    principal, candidate, activeQr, request, claimId,
                    AttendanceFailureReason.STATE_CONFLICT, distance, attemptedAt);
        }
        if (isReadyWindowClosed(lock, attemptedAt)) {
            return reject(
                    principal, candidate, activeQr, request, claimId,
                    AttendanceFailureReason.TIME_WINDOW_CLOSED, distance, attemptedAt);
        }

        insertSuccess(
                principal, candidate, activeQr, request, AttendanceType.CHECK_IN,
                distance, attemptedAt, null);
        requireTransitioned(workLifecycleCommandService.transition(
                lock.workCaseId(), WorkCaseStatus.READY, WorkCaseStatus.IN_PROGRESS));

        boolean late = attemptedAt.isAfter(lock.startsAt());
        AttendanceScanResponse response = AttendanceScanResponse.recorded(
                candidate.getWorkCaseId(),
                AttendanceType.CHECK_IN,
                ApiTimes.toInstant(attemptedAt),
                late,
                lateMinutes(lock.startsAt(), attemptedAt),
                null,
                null);
        return succeed(claimId, response);
    }

    private AttendanceScanOutcome checkOut(
            AuthPrincipal principal,
            AttendanceScanCandidateRow candidate,
            QrTokenRow activeQr,
            AttendanceScanRequest request,
            long claimId,
            BigDecimal distance,
            LocalDateTime attemptedAt) {
        boolean early = attemptedAt.isBefore(candidate.getEndsAt());
        if (early && !request.isEarlyCheckoutConfirmed()) {
            // 확인 전에는 성공 행도 거절 행도, 상태 변경도 만들지 않습니다. 그래도 이 응답은
            // 24시간 Replay 대상이므로 Claim은 완료합니다.
            AttendanceScanConfirmationResponse response = AttendanceScanConfirmationResponse.of(
                    candidate.getWorkCaseId(), ApiTimes.toInstant(candidate.getEndsAt()));
            return succeed(claimId, response);
        }

        WorkLifecycleSnapshot lock = workLifecycleCommandService.lock(candidate.getWorkCaseId());
        if (lock == null || lock.status() != WorkCaseStatus.IN_PROGRESS) {
            return reject(
                    principal, candidate, activeQr, request, claimId,
                    AttendanceFailureReason.STATE_CONFLICT, distance, attemptedAt);
        }
        if (!attemptedAt.isBefore(AttendanceWindowPolicy.checkOutMissingAt(lock.endsAt()))) {
            return reject(
                    principal, candidate, activeQr, request, claimId,
                    AttendanceFailureReason.TIME_WINDOW_CLOSED, distance, attemptedAt);
        }

        LocalDateTime earlyConfirmedAt = early ? attemptedAt : null;
        insertSuccess(
                principal, candidate, activeQr, request, AttendanceType.CHECK_OUT,
                distance, attemptedAt, earlyConfirmedAt);
        requireTransitioned(workLifecycleCommandService.transition(
                lock.workCaseId(), WorkCaseStatus.IN_PROGRESS, WorkCaseStatus.COMPLETED));

        // 지급을 예약합니다. Wallet·Escrow 금액과 원장은 바뀌지 않습니다. 영향 행이 정확히
        // 1행이 아니면 근태만 완료되고 지급이 예약되지 않은 채 commit되므로 전체를 되돌립니다.
        LocalDateTime settlementDueAt = attemptedAt.plusHours(SETTLEMENT_DUE_HOURS);
        settlementReservationService.schedulePayout(lock.workCaseId(), settlementDueAt);

        AttendanceScanResponse response = AttendanceScanResponse.recorded(
                candidate.getWorkCaseId(),
                AttendanceType.CHECK_OUT,
                ApiTimes.toInstant(attemptedAt),
                false,
                null,
                ApiTimes.toInstant(earlyConfirmedAt),
                ApiTimes.toInstant(settlementDueAt));
        return succeed(claimId, response);
    }

    /**
     * 완성된 응답으로 멱등 Claim을 완료하고 성공 결과를 돌려줍니다.
     *
     * <p>{@link IdempotencyClaimService#complete}는 이 Transaction 안에서 마지막 DB 변경으로
     * 호출해야 합니다. Transaction 밖에서 부르면 참여할 Transaction이 없어 실패하고, 근태와
     * 상태 전이는 이미 commit된 채 응답만 유실됩니다.</p>
     */
    private AttendanceScanOutcome succeed(long claimId, AttendanceScanResult response) {
        claimService.complete(claimId, RESPONSE_HTTP_STATUS, replayCodec.writeResponseBody(response));
        return AttendanceScanOutcome.success(response);
    }

    /**
     * 후보 조회와 잠금 사이에 READY 시간창이 닫혔는지 다시 봅니다.
     *
     * <p>Scheduler가 그 사이에 결근으로 옮겼다면 상태 확인에서 걸리지만, 상태가 아직
     * READY인 채로 경계만 지난 순간도 있습니다.</p>
     */
    private boolean isReadyWindowClosed(WorkLifecycleSnapshot lock, LocalDateTime attemptedAt) {
        return attemptedAt.isBefore(AttendanceWindowPolicy.readyOpensAt(lock.startsAt()))
                || !attemptedAt.isBefore(AttendanceWindowPolicy.noShowAt(lock.startsAt()));
    }

    /** 지각은 저장 상태가 아니라 시작 시각과의 양의 차이를 분 단위로 올린 파생값입니다. */
    private static Integer lateMinutes(LocalDateTime startsAt, LocalDateTime attemptedAt) {
        if (!attemptedAt.isAfter(startsAt)) {
            return null;
        }
        return (int) Math.ceil(Duration.between(startsAt, attemptedAt).toNanos() / 60_000_000_000d);
    }

    private AttendanceScanOutcome reject(
            AuthPrincipal principal,
            AttendanceScanCandidateRow candidate,
            QrTokenRow activeQr,
            AttendanceScanRequest request,
            long claimId,
            AttendanceFailureReason reason,
            BigDecimal distance,
            LocalDateTime attemptedAt) {
        scanAuditor.recordRejection(
                candidate.getWorkCaseId(),
                principal.getUserId(),
                activeQr.getId(),
                candidate.getScanType(),
                reason,
                toStoredDistance(distance),
                request.getAccuracyMeters(),
                ApiTimes.toLocalDateTime(request.getCapturedAt()),
                attemptedAt);
        // 거절은 저장·재생 대상이 아닙니다. Claim은 Transaction 밖의 호출부가 지웁니다.
        return AttendanceScanOutcome.rejected(reason);
    }

    /**
     * 조건부 전이는 잠금 안에서 상태를 이미 확인했으므로 실패할 수 없습니다.
     *
     * <p>그래도 0행이면 잠금 밖에서 상태를 바꾼 경로가 생겼다는 뜻입니다. 성공 근태 행과
     * 상태가 어긋난 채 남지 않도록 Transaction 전체를 되돌립니다.</p>
     */
    private void requireTransitioned(boolean transitioned) {
        if (!transitioned) {
            throw new IllegalStateException("근무 상태 전이가 예상과 다르게 실패했습니다.");
        }
    }

    private void insertSuccess(
            AuthPrincipal principal,
            AttendanceScanCandidateRow candidate,
            QrTokenRow activeQr,
            AttendanceScanRequest request,
            AttendanceType type,
            BigDecimal distance,
            LocalDateTime attemptedAt,
            LocalDateTime earlyCheckoutConfirmedAt) {
        AttendanceRecordInsertParam param = AttendanceRecordInsertParam.builder()
                .workCaseId(candidate.getWorkCaseId())
                .workerId(principal.getUserId())
                .qrTokenId(activeQr.getId())
                .attendanceType(type)
                .capturedAt(ApiTimes.toLocalDateTime(request.getCapturedAt()))
                .attemptedAt(attemptedAt)
                .distanceMeters(toStoredDistance(distance))
                .accuracyMeters(request.getAccuracyMeters())
                .result(AttendanceResult.SUCCESS)
                .earlyCheckoutConfirmedAt(earlyCheckoutConfirmedAt)
                .build();
        try {
            if (attendanceRecordMapper.insertAttempt(param) != 1) {
                throw new IllegalStateException("근태 기록을 저장하지 못했습니다.");
            }
        } catch (DuplicateKeyException exception) {
            // 행 잠금을 잡았더라도 최종 보장은 uk_attendance_records_success입니다.
            throw AttendanceScanException.stateConflict("이미 기록된 근태입니다.");
        }
    }

    private QrTokenRow requireActiveQr(AuthPrincipal principal, QrTokenPayload payload) {
        QrTokenRow activeQr =
                qrTokenMapper.findActiveByWorkplaceIdForUpdate(payload.workplaceId());
        if (activeQr == null
                || !MessageDigest.isEqual(activeQr.getTokenNonce(), payload.nonce())) {
            scanAuditor.logUnresolvedRejection(principal.getUserId(), "QR_TOKEN_REVOKED");
            throw AttendanceScanException.qrRevoked();
        }
        return activeQr;
    }

    /**
     * 지금 처리할 근무가 정확히 한 건인지 확인합니다.
     *
     * <p>활성 후보가 없으면 같은 시간 범위의 완료 후보를 확인해 "이미 완료"와 "근무 없음"을
     * 가릅니다. 서버는 시각이나 ID로 임의 선택하지 않습니다.</p>
     */
    private AttendanceScanCandidateRow requireSingleCandidate(
            AuthPrincipal principal, long workplaceId, LocalDateTime attemptedAt) {
        List<AttendanceScanCandidateRow> candidates =
                attendanceRecordMapper.findActiveScanCandidates(
                        principal.getUserId(),
                        workplaceId,
                        AttendanceWindowPolicy.readyLatestStartsAt(attemptedAt),
                        AttendanceWindowPolicy.readyEarliestStartsAt(attemptedAt),
                        AttendanceWindowPolicy.checkOutEarliestEndsAt(attemptedAt));
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.size() > 1) {
            scanAuditor.logUnresolvedRejection(principal.getUserId(), "WORK_CASE_AMBIGUOUS");
            throw AttendanceScanException.workCaseAmbiguous();
        }

        int completed = attendanceRecordMapper.countCompletedScanCandidates(
                principal.getUserId(),
                workplaceId,
                AttendanceWindowPolicy.readyLatestStartsAt(attemptedAt),
                AttendanceWindowPolicy.checkOutEarliestEndsAt(attemptedAt));
        if (completed == 1) {
            scanAuditor.logUnresolvedRejection(principal.getUserId(), "ALREADY_COMPLETED");
            throw AttendanceScanException.alreadyCompleted();
        }
        if (completed > 1) {
            scanAuditor.logUnresolvedRejection(principal.getUserId(), "WORK_CASE_AMBIGUOUS");
            throw AttendanceScanException.workCaseAmbiguous();
        }
        scanAuditor.logUnresolvedRejection(principal.getUserId(), "WORK_CASE_NOT_FOUND");
        throw AttendanceScanException.workCaseNotFound();
    }

    /**
     * 현재 사업장 좌표를 기준으로 거리를 계산합니다.
     *
     * <p>판정은 반올림하지 않은 Double 값으로 하고, 저장할 때만 소수 2자리로 줄인다.
     * 반올림한 값으로 판정하면 100m를 아주 조금 넘은 요청이 통과합니다.</p>
     */
    private BigDecimal distanceMeters(
            WorkplaceLocationSnapshot workplace, AttendanceScanRequest request) {
        double meters = HaversineDistanceCalculator.distanceMeters(
                workplace.latitude().doubleValue(),
                workplace.longitude().doubleValue(),
                request.getLatitude().doubleValue(),
                request.getLongitude().doubleValue());
        return BigDecimal.valueOf(meters);
    }

    /** 저장 직전에만 컬럼 정밀도로 줄입니다. */
    private static BigDecimal toStoredDistance(BigDecimal distance) {
        return distance == null ? null : distance.setScale(DISTANCE_SCALE, RoundingMode.HALF_UP);
    }
}
