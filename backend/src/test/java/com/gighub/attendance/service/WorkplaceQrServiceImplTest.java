package com.gighub.attendance.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;

import com.gighub.attendance.dto.WorkplaceQrReissueResponse;
import com.gighub.attendance.dto.WorkplaceQrResponse;
import com.gighub.attendance.exception.WorkplaceQrIntegrityException;
import com.gighub.attendance.mapper.QrTokenMapper;
import com.gighub.attendance.mapper.result.QrTokenRow;
import com.gighub.attendance.qr.QrHmacKeys;
import com.gighub.attendance.qr.QrTokenCodec;
import com.gighub.attendance.service.impl.WorkplaceQrServiceImpl;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.ConflictException;
import com.gighub.common.exception.ResourceNotFoundException;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.member.domain.UserRole;
import com.gighub.workplace.service.WorkplaceOwnershipService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 고정 QR 조회 계약을 DB 없이 검증합니다. */
class WorkplaceQrServiceImplTest {

    private static final byte[] NONCE = {
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
    };

    private final WorkplaceOwnershipService workplaceService =
            mock(WorkplaceOwnershipService.class);
    private final QrTokenMapper qrTokenMapper = mock(QrTokenMapper.class);
    private final QrTokenCodec codec = new QrTokenCodec(new QrHmacKeys("k1",
            Map.of("k1", "01234567890123456789012345678901".getBytes(StandardCharsets.US_ASCII))));
    private final WorkplaceQrIssuer qrIssuer = mock(WorkplaceQrIssuer.class);
    private final WorkplaceQrServiceImpl service =
            new WorkplaceQrServiceImpl(workplaceService, qrTokenMapper, codec, qrIssuer);

    private static final byte[] NEW_NONCE = {
        16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1
    };

    @Test
    void reissueRevokesTheCurrentQrBeforeIssuingTheNewOne() {
        when(qrTokenMapper.revokeActiveByWorkplaceId(42L)).thenReturn(1);
        when(qrIssuer.issueActive(42L, 7L)).thenReturn(NEW_NONCE);

        WorkplaceQrReissueResponse response = service.reissue(owner(7L), 42L);

        // 순서가 뒤집히면 부분 유니크가 발급을 거부합니다. 순서 자체가 계약입니다.
        InOrder order = inOrder(qrTokenMapper, qrIssuer);
        order.verify(qrTokenMapper).revokeActiveByWorkplaceId(42L);
        order.verify(qrIssuer).issueActive(42L, 7L);

        assertEquals(42L, response.getWorkplaceId());
        // 응답 Token은 방금 저장한 nonce로 서명되어야 합니다. 다른 nonce로 서명하면 사장이
        // 인쇄한 QR과 DB의 활성 QR이 영구히 어긋납니다.
        assertEquals(codec.sign(42L, NEW_NONCE), response.getQrToken());
        assertNotNull(response.getReissuedAt());
    }

    /** 활성 QR이 없어도 재발급은 성공해야 합니다. 조회 500 상태에서 유일한 복구 경로입니다. */
    @Test
    void reissueSucceedsWhenNoActiveQrExists() {
        when(qrTokenMapper.revokeActiveByWorkplaceId(42L)).thenReturn(0);
        when(qrIssuer.issueActive(42L, 7L)).thenReturn(NEW_NONCE);

        assertTrue(codec.verify(service.reissue(owner(7L), 42L).getQrToken()).isPresent());
    }

    @Test
    void reissueRejectsNonOwnerBeforeTouchingStorage() {
        AuthPrincipal worker = new AuthPrincipal(9L, UserRole.WORKER, "김근로");

        assertThrows(RoleMismatchException.class, () -> service.reissue(worker, 42L));

        verifyNoInteractions(workplaceService);
        verifyNoInteractions(qrTokenMapper);
        verifyNoInteractions(qrIssuer);
    }

    @Test
    void reissueReportsNotFoundForAWorkplaceTheCallerDoesNotOwn() {
        doThrow(new ResourceNotFoundException("사업장을 찾을 수 없습니다."))
                .when(workplaceService).lockOwnedActiveWorkplace(anyLong(), anyLong());

        assertThrows(ResourceNotFoundException.class, () -> service.reissue(owner(7L), 42L));

        verifyNoInteractions(qrTokenMapper);
        verifyNoInteractions(qrIssuer);
    }

    /** 동시 재발급 경쟁의 패자는 Unique 위반을 승인된 충돌 응답으로 받습니다. */
    @Test
    void reissueTranslatesDuplicateActiveQrIntoConflict() {
        when(qrTokenMapper.revokeActiveByWorkplaceId(42L)).thenReturn(1);
        when(qrIssuer.issueActive(42L, 7L)).thenThrow(new DuplicateKeyException("duplicate"));

        assertThrows(ConflictException.class, () -> service.reissue(owner(7L), 42L));
    }

    @Test
    void returnsTokenSignedFromTheStoredNonce() {
        when(qrTokenMapper.findActiveByWorkplaceId(42L)).thenReturn(row());

        WorkplaceQrResponse response = service.findQr(owner(7L), 42L);

        assertEquals(42L, response.getWorkplaceId());
        assertEquals(codec.sign(42L, NONCE), response.getQrToken());
        assertTrue(codec.verify(response.getQrToken()).isPresent());
    }

    @Test
    void repeatedLookupsReturnTheSameToken() {
        when(qrTokenMapper.findActiveByWorkplaceId(42L)).thenReturn(row());

        assertEquals(
                service.findQr(owner(7L), 42L).getQrToken(),
                service.findQr(owner(7L), 42L).getQrToken());
    }

    @Test
    void rejectsNonOwnerBeforeTouchingStorage() {
        AuthPrincipal worker = new AuthPrincipal(9L, UserRole.WORKER, "김근로");

        assertThrows(RoleMismatchException.class, () -> service.findQr(worker, 42L));

        verifyNoInteractions(workplaceService);
        verifyNoInteractions(qrTokenMapper);
    }

    /** 남의 사업장과 없는 사업장을 구분하면 식별자의 존재가 드러납니다. 둘 다 404입니다. */
    @Test
    void reportsNotFoundForAWorkplaceTheCallerDoesNotOwn() {
        doThrow(new ResourceNotFoundException("사업장을 찾을 수 없습니다."))
                .when(workplaceService).requireOwnedActiveWorkplace(anyLong(), anyLong());

        assertThrows(ResourceNotFoundException.class, () -> service.findQr(owner(7L), 42L));

        verifyNoInteractions(qrTokenMapper);
    }

    /** 활성 QR 없는 ACTIVE 사업장은 정상 상태가 아니므로 빈 응답으로 감추지 않습니다. */
    @Test
    void reportsIntegrityFailureWhenTheActiveWorkplaceHasNoQr() {
        when(qrTokenMapper.findActiveByWorkplaceId(42L)).thenReturn(null);

        assertThrows(WorkplaceQrIntegrityException.class, () -> service.findQr(owner(7L), 42L));
    }

    private static QrTokenRow row() {
        QrTokenRow row = new QrTokenRow();
        row.setId(1L);
        row.setWorkplaceId(42L);
        row.setTokenNonce(NONCE);
        row.setCreatedAt(LocalDateTime.of(2026, 8, 7, 9, 0));
        return row;
    }

    private static AuthPrincipal owner(long userId) {
        return new AuthPrincipal(userId, UserRole.OWNER, "김사장");
    }
}
