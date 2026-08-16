package com.gighub.document.service;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.document.exception.DocumentNotFoundException;
import com.gighub.document.mapper.ContractDocumentWriteMapper;
import com.gighub.document.mapper.result.DocumentOwnershipRow;
import com.gighub.member.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 보건증 공유 철회의 소유권·유형 판정과 멱등 성공 경계를 검증합니다. */
class HealthCertificateShareRevokeServiceImplTest {

    private static final long WORKER_ID = 7L;
    private static final long DOCUMENT_ID = 11L;
    private static final long WORKPLACE_ID = 3L;
    private static final LocalDateTime NOW =
            LocalDate.of(2026, 8, 15).atStartOfDay();

    private final ContractDocumentWriteMapper documentMapper = mock(ContractDocumentWriteMapper.class);

    private final HealthCertificateShareRevokeServiceImpl service =
            new HealthCertificateShareRevokeServiceImpl(
                    documentMapper,
                    Clock.fixed(
                            NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul")));

    @Test
    void rejectsAnOwnerWithRoleMismatchBeforeTouchingTheDatabase() {
        assertThrows(RoleMismatchException.class,
                () -> service.revoke(owner(), DOCUMENT_ID, WORKPLACE_ID));

        verifyNoInteractions(documentMapper);
    }

    @Test
    void hidesAMissingDocumentBehindNotFound() {
        when(documentMapper.lockOwnDocument(DOCUMENT_ID, WORKER_ID)).thenReturn(null);

        assertThrows(DocumentNotFoundException.class,
                () -> service.revoke(worker(), DOCUMENT_ID, WORKPLACE_ID));

        verify(documentMapper, never())
                .revokeActiveSharesByWorkplace(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any());
    }

    /** 근로계약서는 보건증 공유 대상이 아니므로 존재를 숨기는 같은 404를 쓴다. */
    @Test
    void hidesAnEmploymentContractBehindNotFound() {
        when(documentMapper.lockOwnDocument(DOCUMENT_ID, WORKER_ID)).thenReturn(
                DocumentOwnershipRow.builder()
                        .documentType("EMPLOYMENT_CONTRACT")
                        .status("ACTIVE")
                        .build());

        assertThrows(DocumentNotFoundException.class,
                () -> service.revoke(worker(), DOCUMENT_ID, WORKPLACE_ID));

        verify(documentMapper, never())
                .revokeActiveSharesByWorkplace(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void revokesOnlyTheSharesOfTheGivenWorkplace() {
        when(documentMapper.lockOwnDocument(DOCUMENT_ID, WORKER_ID)).thenReturn(
                DocumentOwnershipRow.builder()
                        .documentType("HEALTH_CERTIFICATE")
                        .status("ACTIVE")
                        .build());

        service.revoke(worker(), DOCUMENT_ID, WORKPLACE_ID);

        ArgumentCaptor<LocalDateTime> revokedAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(documentMapper).revokeActiveSharesByWorkplace(
                org.mockito.ArgumentMatchers.eq(DOCUMENT_ID),
                org.mockito.ArgumentMatchers.eq(WORKPLACE_ID),
                revokedAtCaptor.capture());
        assertEquals(NOW, revokedAtCaptor.getValue());
    }

    /**
     * 이미 삭제된 문서(모든 ACTIVE 공유가 이미 철회됨)에 대한 반복 호출도 소유자 요청이면
     * 그대로 진행한다. 대상 없는 UPDATE는 자연히 0행이고 오류가 아니다.
     */
    @Test
    void proceedsForAnAlreadyDeletedOwnHealthCertificate() {
        when(documentMapper.lockOwnDocument(DOCUMENT_ID, WORKER_ID)).thenReturn(
                DocumentOwnershipRow.builder()
                        .documentType("HEALTH_CERTIFICATE")
                        .status("DELETED")
                        .build());

        service.revoke(worker(), DOCUMENT_ID, WORKPLACE_ID);

        verify(documentMapper).revokeActiveSharesByWorkplace(
                org.mockito.ArgumentMatchers.eq(DOCUMENT_ID),
                org.mockito.ArgumentMatchers.eq(WORKPLACE_ID),
                org.mockito.ArgumentMatchers.any());
    }

    private AuthPrincipal worker() {
        return new AuthPrincipal(WORKER_ID, UserRole.WORKER, "이알바");
    }

    private AuthPrincipal owner() {
        return new AuthPrincipal(5L, UserRole.OWNER, "김대표");
    }
}
