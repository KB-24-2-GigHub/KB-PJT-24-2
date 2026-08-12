package com.gighub.attendance.service.impl;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import com.gighub.attendance.domain.AttendanceFailureReason;
import com.gighub.attendance.domain.AttendanceScanFingerprint;
import com.gighub.attendance.service.result.AttendanceScanOutcome;
import com.gighub.attendance.dto.AttendanceScanRequest;
import com.gighub.attendance.dto.AttendanceScanResult;
import com.gighub.attendance.exception.AttendanceScanException;
import com.gighub.attendance.qr.QrTokenCodec;
import com.gighub.attendance.qr.QrTokenPayload;
import com.gighub.attendance.service.AttendanceScanAuditor;
import com.gighub.attendance.service.AttendanceScanExecutor;
import com.gighub.attendance.service.AttendanceScanReplayCodec;
import com.gighub.attendance.service.AttendanceScanService;
import com.gighub.attendance.service.result.AttendanceScanOutput;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.idempotency.IdempotencyClaimResult;
import com.gighub.idempotency.IdempotencyClaimService;
import com.gighub.idempotency.IdempotencyKeys;
import com.gighub.member.domain.UserRole;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 스캔 요청의 권한·Token을 확인하고 멱등 계약 안에서 판정을 실행합니다.
 *
 * <p>DB 판정과 응답 완성, 멱등 Claim 완료는 {@link AttendanceScanExecutor}가 한
 * Transaction으로 처리합니다. 여기서는 그 Transaction의 시작(Claim 선점)과 끝(재시도·
 * 뒷정리·오류 변환)만 다룹니다. Claim 완료를 이 클래스에서 부르면
 * {@code Propagation.MANDATORY}가 참여할 Transaction을 찾지 못해 실패하므로, 완료는 반드시
 * Executor 안에서 끝나야 합니다.</p>
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
     * 남겨두면 사용자가 위치를 고쳐 같은 Key로 재시도할 수 없습니다. Claim 완료는 이미
     * Executor 안에서 끝났으므로 성공 경로에서는 여기서 더 할 일이 없습니다.</p>
     */
    private AttendanceScanResult runWithRetry(
            AuthPrincipal principal,
            AttendanceScanRequest request,
            QrTokenPayload payload,
            long claimId,
            Instant receivedAt) {
        for (int attempt = 1; attempt <= MAX_TRANSACTION_ATTEMPTS; attempt++) {
            AttendanceScanOutcome outcome;
            try {
                outcome = scanExecutor.execute(principal, request, payload, claimId, receivedAt);
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
                throw toRejectionException(outcome.getFailureReason());
            }
            return outcome.getResponse();
        }
        throw new IllegalStateException("근태 스캔 재시도 횟수 계산이 올바르지 않습니다.");
    }

    /** 감사에 남긴 사유를 승인된 응답 오류로 옮깁니다. */
    private AttendanceScanException toRejectionException(AttendanceFailureReason reason) {
        switch (reason) {
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
