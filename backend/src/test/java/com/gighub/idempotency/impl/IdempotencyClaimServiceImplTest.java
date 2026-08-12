package com.gighub.idempotency.impl;

import com.gighub.common.exception.ConflictException;
import com.gighub.common.exception.ValidationException;
import com.gighub.idempotency.IdempotencyClaimResult;
import com.gighub.idempotency.exception.IdempotencyClaimKeyReusedException;
import com.gighub.idempotency.mapper.IdempotencyClaimMapper;
import com.gighub.idempotency.mapper.param.IdempotencyClaimInsertParam;
import com.gighub.idempotency.mapper.result.IdempotencyClaimRow;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Claim 선점·Replay·충돌 판정과 보존 경계를 확인합니다.
 */
class IdempotencyClaimServiceImplTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 20, 10, 0);
    private static final long USER_ID = 11L;
    private static final String OPERATION = "INVITATION_ACCEPT";
    private static final String KEY = "accept-key-0001";
    private static final long CLAIM_ID = 77L;

    private final StubClaimMapper mapper = new StubClaimMapper();

    @Test
    void firstRequestClaimsAndKeepsRetentionWindow() {
        IdempotencyClaimResult result = service().claim(USER_ID, OPERATION, KEY, fingerprint(1));

        assertFalse(result.isReplay());
        assertEquals(CLAIM_ID, result.getClaimId());

        IdempotencyClaimInsertParam inserted = mapper.inserted.get(0);
        assertEquals(USER_ID, inserted.getUserId());
        assertEquals(OPERATION, inserted.getOperationCode());
        assertEquals(KEY, inserted.getIdempotencyKey());
        // 저장한 성공 결과는 24시간 보존합니다.
        assertEquals(NOW.plusHours(24L), inserted.getExpiresAt());
    }

    @Test
    void completedSameRequestReplaysStoredResponseWithoutRechecking() {
        mapper.existing = completedClaim(fingerprint(1));

        IdempotencyClaimResult result = service().claim(USER_ID, OPERATION, KEY, fingerprint(1));

        assertTrue(result.isReplay());
        assertEquals(200, result.getResponseHttpStatus());
        assertEquals("{\"data\":{\"workCaseId\":123}}", result.getResponseBody());
        // Replay는 저장 결과 조회이므로 새 Claim을 만들지 않습니다.
        assertTrue(mapper.inserted.isEmpty());
    }

    @Test
    void inFlightSameRequestConflictsWithoutWaiting() {
        mapper.existing = processingClaim(fingerprint(1));

        ConflictException failure = assertThrows(
                ConflictException.class,
                () -> service().claim(USER_ID, OPERATION, KEY, fingerprint(1))
        );

        assertEquals("같은 요청을 처리하고 있습니다.", failure.getMessage());
    }

    @Test
    void differentRequestWithSameKeyIsRejectedRegardlessOfStatus() {
        mapper.existing = completedClaim(fingerprint(1));
        assertThrows(
                IdempotencyClaimKeyReusedException.class,
                () -> service().claim(USER_ID, OPERATION, KEY, fingerprint(2))
        );

        mapper.existing = processingClaim(fingerprint(1));
        assertThrows(
                IdempotencyClaimKeyReusedException.class,
                () -> service().claim(USER_ID, OPERATION, KEY, fingerprint(2))
        );
    }

    @Test
    void expiredClaimIsClearedSoTheKeyCanBeUsedAgain() {
        mapper.existing = IdempotencyClaimRow.builder()
                .id(CLAIM_ID)
                .userId(USER_ID)
                .operationCode(OPERATION)
                .requestFingerprint(fingerprint(1))
                .status("COMPLETED")
                .responseHttpStatus(200)
                .responseBody("{\"data\":{}}")
                // 보존 기간이 이미 지난 Claim입니다.
                .expiresAt(NOW)
                .build();

        IdempotencyClaimResult result = service().claim(USER_ID, OPERATION, KEY, fingerprint(2));

        assertFalse(result.isReplay(), "만료된 Claim은 Replay 대상이 아닙니다.");
        assertEquals(List.of(NOW), mapper.purgedBefore);
    }

    @Test
    void malformedKeysAreRejectedBeforeAnyStorageAccess() {
        for (String invalid : new String[]{null, "", " ", "has space", "key\twith\ttab"}) {
            assertThrows(
                    ValidationException.class,
                    () -> service().claim(USER_ID, OPERATION, invalid, fingerprint(1))
            );
        }
        assertThrows(
                ValidationException.class,
                () -> service().claim(USER_ID, OPERATION, "x".repeat(101), fingerprint(1))
        );

        assertTrue(mapper.inserted.isEmpty());
    }

    @Test
    void keyIsNotEchoedInValidationFailures() {
        ValidationException failure = assertThrows(
                ValidationException.class,
                () -> service().claim(USER_ID, OPERATION, "bad key value", fingerprint(1))
        );

        assertFalse(failure.getMessage().contains("bad key value"));
    }

    @Test
    void completingAClaimStoresTheFirstSuccessfulResponse() {
        service().complete(CLAIM_ID, 200, "{\"data\":{\"workCaseId\":123}}");

        assertEquals(1, mapper.completed.size());
        assertEquals(CLAIM_ID, mapper.completed.get(0));
    }

    @Test
    void completingAnAlreadyFinishedClaimFailsInsteadOfOverwriting() {
        mapper.completeResult = 0;

        assertThrows(
                IllegalStateException.class,
                () -> service().complete(CLAIM_ID, 200, "{\"data\":{}}")
        );
    }

    @Test
    void abandoningRemovesOnlyProcessingClaims() {
        service().abandon(CLAIM_ID);

        assertEquals(List.of(CLAIM_ID), mapper.abandoned);
    }

    @Test
    void abandoningFailsWhenTheOwnedProcessingClaimWasNotDeleted() {
        mapper.deleteProcessingResult = 0;

        assertThrows(IllegalStateException.class, () -> service().abandon(CLAIM_ID));
    }

    private IdempotencyClaimServiceImpl service() {
        return new IdempotencyClaimServiceImpl(
                mapper, Clock.fixed(NOW.atZone(SEOUL).toInstant(), SEOUL));
    }

    private static byte[] fingerprint(int seed) {
        byte[] value = new byte[32];
        java.util.Arrays.fill(value, (byte) seed);
        return value;
    }

    private IdempotencyClaimRow processingClaim(byte[] fingerprint) {
        return IdempotencyClaimRow.builder()
                .id(CLAIM_ID)
                .userId(USER_ID)
                .operationCode(OPERATION)
                .requestFingerprint(fingerprint)
                .status("PROCESSING")
                .expiresAt(NOW.plusHours(24L))
                .build();
    }

    private IdempotencyClaimRow completedClaim(byte[] fingerprint) {
        return IdempotencyClaimRow.builder()
                .id(CLAIM_ID)
                .userId(USER_ID)
                .operationCode(OPERATION)
                .requestFingerprint(fingerprint)
                .status("COMPLETED")
                .responseHttpStatus(200)
                .responseBody("{\"data\":{\"workCaseId\":123}}")
                .expiresAt(NOW.plusHours(24L))
                .build();
    }

    /** 선점 실패 경로를 직접 만들어야 해서 Mock 대신 Stub을 씁니다. */
    private static final class StubClaimMapper implements IdempotencyClaimMapper {

        private final List<IdempotencyClaimInsertParam> inserted = new ArrayList<>();
        private final List<Long> completed = new ArrayList<>();
        private final List<Long> abandoned = new ArrayList<>();
        private final List<LocalDateTime> purgedBefore = new ArrayList<>();

        private IdempotencyClaimRow existing;
        private int completeResult = 1;
        private int deleteProcessingResult = 1;

        @Override
        public int insertProcessing(IdempotencyClaimInsertParam param) {
            // 같은 범위의 Claim이 남아 있는 동안에는 UNIQUE 제약이 선점을 막습니다.
            if (existing != null) {
                throw new DuplicateKeyException("uk_idempotency_requests_scope");
            }
            param.setId(CLAIM_ID);
            inserted.add(param);
            return 1;
        }

        @Override
        public IdempotencyClaimRow findForUpdate(
                long userId, String operationCode, String idempotencyKey) {
            return existing;
        }

        @Override
        public int complete(
                long claimId,
                int responseHttpStatus,
                String responseBody,
                LocalDateTime completedAt) {
            completed.add(claimId);
            return completeResult;
        }

        @Override
        public int deleteProcessing(long claimId) {
            abandoned.add(claimId);
            return deleteProcessingResult;
        }

        @Override
        public int deleteExpired(LocalDateTime now) {
            purgedBefore.add(now);
            existing = null;
            return 1;
        }
    }
}
