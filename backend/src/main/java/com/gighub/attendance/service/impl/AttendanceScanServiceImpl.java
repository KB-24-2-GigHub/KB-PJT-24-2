package com.gighub.attendance.service.impl;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import com.gighub.attendance.domain.AttendanceScanFingerprint;
import com.gighub.attendance.domain.AttendanceScanOutcome;
import com.gighub.attendance.domain.AttendanceWindowPolicy;
import com.gighub.attendance.dto.AttendanceScanRequest;
import com.gighub.attendance.dto.AttendanceScanResponse;
import com.gighub.attendance.exception.AttendanceScanException;
import com.gighub.attendance.qr.QrTokenCodec;
import com.gighub.attendance.qr.QrTokenPayload;
import com.gighub.attendance.service.AttendanceScanAuditor;
import com.gighub.attendance.service.AttendanceScanExecutor;
import com.gighub.attendance.service.AttendanceScanReplayCodec;
import com.gighub.attendance.service.AttendanceScanService;
import com.gighub.attendance.service.result.AttendanceScanOutput;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.api.ApiTimes;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.idempotency.IdempotencyClaimResult;
import com.gighub.idempotency.IdempotencyClaimService;
import com.gighub.idempotency.IdempotencyKeys;
import com.gighub.member.domain.UserRole;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 스캔 요청의 권한·Token·좌표 신선도를 확인하고 멱등 계약 안에서 판정을 실행합니다.
 *
 * <p>DB 판정은 {@link AttendanceScanExecutor}가 한 Transaction으로 처리합니다. 거절을 응답
 * 오류로 바꾸는 일과 Claim 뒷정리는 그 Transaction이 끝난 뒤에 해야 하므로 Transaction 밖인
 * 이 클래스가 맡습니다.</p>
 */
@Service
public class AttendanceScanServiceImpl implements AttendanceScanService {

    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Seoul");
    private static final String OPERATION_CODE = "ATTENDANCE_SCAN";

    /** Deadlock·Lock Timeout은 같은 의도를 최대 2회 더 시도해 총 3회까지 실행합니다. */
    private static final int MAX_TRANSACTION_ATTEMPTS = 3;

    private final QrTokenCodec qrTokenCodec;
    private final AttendanceScanExecutor scanExecutor;
    private final AttendanceScanAuditor scanAuditor;
    private final AttendanceScanReplayCodec replayCodec;
    private final IdempotencyClaimService claimService;
    private final Clock clock;

    @Autowired
    public AttendanceScanServiceImpl(
            QrTokenCodec qrTokenCodec,
            AttendanceScanExecutor scanExecutor,
            AttendanceScanAuditor scanAuditor,
            AttendanceScanReplayCodec replayCodec,
            IdempotencyClaimService claimService) {
        this(
                qrTokenCodec,
                scanExecutor,
                scanAuditor,
                replayCodec,
                claimService,
                Clock.system(DATABASE_ZONE));
    }

    /** 경계 시각 테스트에서만 고정 Clock을 주입합니다. */
    AttendanceScanServiceImpl(
            QrTokenCodec qrTokenCodec,
            AttendanceScanExecutor scanExecutor,
            AttendanceScanAuditor scanAuditor,
            AttendanceScanReplayCodec replayCodec,
            IdempotencyClaimService claimService,
            Clock clock) {
        this.qrTokenCodec = qrTokenCodec;
        this.scanExecutor = scanExecutor;
        this.scanAuditor = scanAuditor;
        this.replayCodec = replayCodec;
        this.claimService = claimService;
        this.clock = clock;
    }

    @Override
    public AttendanceScanOutput scan(
            AuthPrincipal principal, String idempotencyKey, AttendanceScanRequest request) {
        requireWorkerRole(principal);
        String rawKey = IdempotencyKeys.validate(idempotencyKey);
        Instant receivedAt = clock.instant();
        requireFreshCapture(principal, request, receivedAt);
        QrTokenPayload payload = decodeToken(principal, request.getQrToken());

        IdempotencyClaimResult claim = claimService.claim(
                principal.getUserId(), OPERATION_CODE, rawKey, fingerprint(request));
        if (claim.isReplay()) {
            // 저장한 결과를 그대로 돌려줍니다. 현재 상태를 다시 보지 않습니다.
            return AttendanceScanOutput.replayed(
                    replayCodec.readResponseBody(claim.getResponseBody()));
        }

        return AttendanceScanOutput.first(
                runWithRetry(principal, request, payload, claim.getClaimId(), receivedAt));
    }

    /**
     * 판정 Transaction을 실행하고 Transaction이 끝난 뒤의 뒷정리를 순서대로 수행합니다.
     *
     * <p>Deadlock과 Lock Timeout은 같은 Claim으로 전체를 다시 실행합니다. 중간 시도에서
     * Claim을 지우면 같은 요청의 재시도 단위가 깨집니다. 최종 소진과 비재시도 실패에서만
     * Claim을 지워 같은 Key로 다시 시도할 수 있게 합니다.</p>
     *
     * <p>거절도 Claim을 지웁니다. 저장하는 결과는 성공과 조기 퇴근 확인 요청뿐이라, 거절을
     * 남겨두면 사용자가 위치를 고쳐 같은 Key로 재시도할 수 없습니다.</p>
     */
    private AttendanceScanResponse runWithRetry(
            AuthPrincipal principal,
            AttendanceScanRequest request,
            QrTokenPayload payload,
            long claimId,
            Instant receivedAt) {
        for (int attempt = 1; attempt <= MAX_TRANSACTION_ATTEMPTS; attempt++) {
            AttendanceScanOutcome outcome;
            try {
                outcome = scanExecutor.execute(
                        principal, request, payload, ApiTimes.toLocalDateTime(receivedAt));
            } catch (PessimisticLockingFailureException transientFailure) {
                if (attempt == MAX_TRANSACTION_ATTEMPTS) {
                    claimService.abandon(claimId);
                    throw AttendanceScanException.temporarilyUnavailable();
                }
                continue;
            } catch (RuntimeException failure) {
                claimService.abandon(claimId);
                throw failure;
            }

            if (outcome.isRejected()) {
                // 감사 행은 이미 commit 되었습니다. Claim만 지웁니다.
                claimService.abandon(claimId);
                throw toRejectionException(outcome);
            }
            return completeAndBuildResponse(outcome, claimId);
        }
        throw new IllegalStateException("근태 스캔 재시도 횟수 계산이 올바르지 않습니다.");
    }

    /**
     * 응답을 만들고 같은 Transaction에서 Claim을 완료해야 하지만, 판정 Transaction은 이미
     * 끝났습니다.
     *
     * <p>{@link IdempotencyClaimService#complete}는 호출자 Transaction에 참여하도록 되어
     * 있으므로 여기서는 새 Transaction으로 저장됩니다. 판정이 commit 된 뒤이므로 저장에
     * 실패하면 Replay만 불가능하고 근태 결과는 남습니다. 판정과 응답 저장을 한 Transaction에
     * 묶으려면 Executor가 응답까지 만들어야 하는데, 그러면 Transaction 안에서 JSON 직렬화가
     * 일어나 잠금 구간이 길어집니다.</p>
     */
    private AttendanceScanResponse completeAndBuildResponse(
            AttendanceScanOutcome outcome, long claimId) {
        AttendanceScanResponse response = toResponse(outcome);
        claimService.complete(claimId, 200, replayCodec.writeResponseBody(response));
        return response;
    }

    private AttendanceScanResponse toResponse(AttendanceScanOutcome outcome) {
        if (outcome.isConfirmationRequired()) {
            return AttendanceScanResponse.confirmationRequired(
                    outcome.getWorkCaseId(), ApiTimes.toInstant(outcome.getScheduledEndAt()));
        }
        return AttendanceScanResponse.recorded(
                outcome.getWorkCaseId(),
                outcome.getScanType(),
                ApiTimes.toInstant(outcome.getRecordedAt()),
                outcome.isLate(),
                outcome.getLateMinutes(),
                ApiTimes.toInstant(outcome.getEarlyCheckoutConfirmedAt()),
                ApiTimes.toInstant(outcome.getSettlementDueAt()));
    }

    /** 감사에 남긴 사유를 승인된 응답 오류로 옮깁니다. */
    private AttendanceScanException toRejectionException(AttendanceScanOutcome outcome) {
        switch (outcome.getFailureReason()) {
            case OUTSIDE_RADIUS:
                return AttendanceScanException.outsideWorkplaceRadius();
            case LOCATION_INACCURATE:
                return AttendanceScanException.workplaceLocationRequired();
            case LOCATION_STALE:
                return AttendanceScanException.locationInvalid("위치 측정 시각을 사용할 수 없습니다.");
            case TIME_WINDOW_CLOSED:
                return AttendanceScanException.stateConflict("지금은 출퇴근할 수 있는 시간이 아닙니다.");
            default:
                return AttendanceScanException.stateConflict(
                        "출퇴근 처리가 다른 요청과 경합했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    /**
     * 측정 시각이 승인된 신선도 범위 안인지 확인합니다.
     *
     * <p>Claim을 선점하기 전에 봅니다. 형식·범위 실패는 저장·재생하지 않는 계약이라 Claim을
     * 만들면 안 됩니다.</p>
     */
    private void requireFreshCapture(
            AuthPrincipal principal, AttendanceScanRequest request, Instant receivedAt) {
        if (!AttendanceWindowPolicy.isCaptureFresh(request.getCapturedAt(), receivedAt)) {
            scanAuditor.logUnresolvedRejection(principal.getUserId(), "LOCATION_STALE");
            throw AttendanceScanException.locationInvalid(
                    "위치 측정 시각이 너무 오래되었거나 미래입니다. 다시 시도해 주세요.");
        }
    }

    /**
     * 구조·Version·HMAC을 한 번에 확인합니다.
     *
     * <p>실패 원인을 나누지 않습니다. 어느 단계까지 통과했는지 알려주면 Token을 맞춰 볼 수
     * 있게 됩니다.</p>
     */
    private QrTokenPayload decodeToken(AuthPrincipal principal, String qrToken) {
        return qrTokenCodec.verify(qrToken)
                .orElseGet(() -> {
                    scanAuditor.logUnresolvedRejection(principal.getUserId(), "QR_TOKEN_INVALID");
                    throw AttendanceScanException.qrInvalid();
                });
    }

    private static byte[] fingerprint(AttendanceScanRequest request) {
        return AttendanceScanFingerprint.of(
                request.getQrToken(),
                request.getLatitude(),
                request.getLongitude(),
                request.getAccuracyMeters(),
                request.getCapturedAt(),
                request.isEarlyCheckoutConfirmed());
    }

    private void requireWorkerRole(AuthPrincipal principal) {
        if (principal.getRole() != UserRole.WORKER) {
            throw new RoleMismatchException("근태 스캔은 WORKER만 사용할 수 있습니다.");
        }
    }
}
