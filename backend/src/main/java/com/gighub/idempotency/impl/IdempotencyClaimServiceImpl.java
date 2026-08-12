package com.gighub.idempotency.impl;

import com.gighub.common.exception.ConflictException;
import com.gighub.idempotency.IdempotencyClaimResult;
import com.gighub.idempotency.IdempotencyClaimService;
import com.gighub.idempotency.IdempotencyKeys;
import com.gighub.idempotency.exception.IdempotencyClaimKeyReusedException;
import com.gighub.idempotency.mapper.IdempotencyClaimMapper;
import com.gighub.idempotency.mapper.param.IdempotencyClaimInsertParam;
import com.gighub.idempotency.mapper.result.IdempotencyClaimRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 승인된 Claim 생명주기를 구현합니다.
 *
 * <p>선점은 사전 조회 없이 INSERT로 시도합니다. "없으면 만든다"를 두 문장으로 나누면 두
 * 요청이 같은 시점에 없음을 확인하고 모두 진입할 수 있어, 판정은 UNIQUE 제약에만 맡깁니다.</p>
 */
@Service
public class IdempotencyClaimServiceImpl implements IdempotencyClaimService {

    /** DB의 DATETIME은 Asia/Seoul 벽시계 값이므로 비교 기준 시각도 같은 지역으로 만듭니다. */
    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Seoul");

    /** 저장한 성공 결과의 보존 기간입니다. */
    private static final Duration RETENTION = Duration.ofHours(24L);

    private static final String PROCESSING = "PROCESSING";
    private static final String COMPLETED = "COMPLETED";

    private static final String IN_PROGRESS_MESSAGE = "같은 요청을 처리하고 있습니다.";

    private final IdempotencyClaimMapper claimMapper;
    private final Clock clock;

    @Autowired
    public IdempotencyClaimServiceImpl(IdempotencyClaimMapper claimMapper) {
        this(claimMapper, Clock.system(DATABASE_ZONE));
    }

    /** 보존 경계를 검증할 때만 고정 Clock을 주입합니다. */
    IdempotencyClaimServiceImpl(IdempotencyClaimMapper claimMapper, Clock clock) {
        this.claimMapper = claimMapper;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyClaimResult claim(
            long userId,
            String operationCode,
            String rawKey,
            byte[] fingerprint) {
        String key = IdempotencyKeys.validate(rawKey);
        Objects.requireNonNull(fingerprint, "fingerprint");
        LocalDateTime now = LocalDateTime.now(clock);

        try {
            return IdempotencyClaimResult.started(insert(userId, operationCode, key, fingerprint, now));
        } catch (DuplicateKeyException alreadyClaimed) {
            return resolveExisting(userId, operationCode, key, fingerprint, now);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(long claimId, int responseHttpStatus, String responseBody) {
        if (claimMapper.complete(
                claimId,
                responseHttpStatus,
                Objects.requireNonNull(responseBody, "responseBody"),
                LocalDateTime.now(clock)) != 1) {
            // 선점한 Claim은 이 Transaction만 완료시킬 수 있습니다. 0행이면 다른 흐름이
            // 먼저 손댄 것이므로 성공으로 넘기지 않고 본 처리를 함께 되돌립니다.
            throw new IllegalStateException("선점한 멱등 Claim을 완료하지 못했습니다.");
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abandon(long claimId) {
        if (claimMapper.deleteProcessing(claimId) != 1) {
            // 새 Aggregate 실행을 허용하려면 이 요청이 선점한 PROCESSING 행 하나가 반드시
            // 사라져야 합니다. 0행을 성공으로 넘기면 같은 Key가 24시간 막힐 수 있습니다.
            throw new IllegalStateException("처리 중인 멱등 Claim을 정리하지 못했습니다.");
        }
    }

    private long insert(
            long userId,
            String operationCode,
            String key,
            byte[] fingerprint,
            LocalDateTime now) {
        IdempotencyClaimInsertParam param = IdempotencyClaimInsertParam.builder()
                .userId(userId)
                .operationCode(operationCode)
                .idempotencyKey(key)
                .requestFingerprint(fingerprint)
                .expiresAt(now.plus(RETENTION))
                .build();
        claimMapper.insertProcessing(param);

        return Objects.requireNonNull(param.getId(), "선점한 멱등 Claim 식별자");
    }

    /**
     * 이미 있는 Claim의 상태와 Fingerprint로 결과를 정합니다.
     *
     * <p>Fingerprint를 먼저 봅니다. 내용이 다른 요청은 처리 중이든 완료든 같은 Key를 쓸 수
     * 없으므로, 상태별 분기보다 앞에 두어야 판정이 일관됩니다.</p>
     */
    private IdempotencyClaimResult resolveExisting(
            long userId,
            String operationCode,
            String key,
            byte[] fingerprint,
            LocalDateTime now) {
        IdempotencyClaimRow existing = claimMapper.findForUpdate(userId, operationCode, key);
        if (existing == null) {
            // 중복으로 막혔던 행이 사이에서 사라졌습니다. 만료 정리와 겹친 경우이므로
            // 한 번만 다시 선점합니다.
            return IdempotencyClaimResult.started(
                    insert(userId, operationCode, key, fingerprint, now));
        }

        if (!existing.getExpiresAt().isAfter(now)) {
            // 보존 기간이 지난 Claim은 없는 것과 같습니다. 지우지 않으면 UNIQUE 제약 때문에
            // 같은 Key를 새 요청에 다시 쓸 수 없습니다.
            claimMapper.deleteExpired(now);
            return IdempotencyClaimResult.started(
                    insert(userId, operationCode, key, fingerprint, now));
        }

        if (!MessageDigest.isEqual(existing.getRequestFingerprint(), fingerprint)) {
            throw new IdempotencyClaimKeyReusedException();
        }
        if (COMPLETED.equals(existing.getStatus())) {
            return IdempotencyClaimResult.replay(
                    existing.getResponseHttpStatus(), existing.getResponseBody());
        }
        if (PROCESSING.equals(existing.getStatus())) {
            // 앞선 요청의 본 처리 잠금을 기다리지 않고 즉시 충돌로 끝냅니다. 기다리면 두
            // 요청이 같은 자원을 두고 줄을 서다 함께 Timeout에 걸립니다.
            throw new ConflictException(IN_PROGRESS_MESSAGE);
        }

        throw new IllegalStateException("알 수 없는 멱등 Claim 상태입니다.");
    }
}
