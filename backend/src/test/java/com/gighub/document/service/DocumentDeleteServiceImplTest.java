package com.gighub.document.service;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.document.exception.ContractRetentionRequiredException;
import com.gighub.document.exception.DocumentNotFoundException;
import com.gighub.document.mapper.ContractDocumentWriteMapper;
import com.gighub.document.mapper.result.DocumentOwnershipRow;
import com.gighub.member.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentDeleteServiceImplTest {

    private static final long DOCUMENT_ID = 9L;
    private static final AuthPrincipal WORKER = new AuthPrincipal(1L, UserRole.WORKER, "이알바");

    @Mock
    private ContractDocumentWriteMapper documentMapper;

    private DocumentDeleteServiceImpl service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-14T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        service = new DocumentDeleteServiceImpl(documentMapper, clock);
    }

    @Test
    void deletesAnActiveHealthCertificateAndRevokesItsActiveShares() {
        when(documentMapper.lockOwnDocument(DOCUMENT_ID, 1L)).thenReturn(
                DocumentOwnershipRow.builder().documentType("HEALTH_CERTIFICATE").status("ACTIVE").build());
        when(documentMapper.deleteHealthCertificate(DOCUMENT_ID)).thenReturn(1);

        service.delete(WORKER, DOCUMENT_ID);

        verify(documentMapper).deleteHealthCertificate(DOCUMENT_ID);
        verify(documentMapper).revokeActiveShares(eq(DOCUMENT_ID), any());
    }

    @Test
    void repeatingDeleteOnAnAlreadyDeletedHealthCertificateIsANoOp() {
        when(documentMapper.lockOwnDocument(DOCUMENT_ID, 1L)).thenReturn(
                DocumentOwnershipRow.builder().documentType("HEALTH_CERTIFICATE").status("DELETED").build());

        service.delete(WORKER, DOCUMENT_ID);

        verify(documentMapper, never()).deleteHealthCertificate(anyLong());
        verify(documentMapper, never()).revokeActiveShares(anyLong(), any());
    }

    @Test
    void rejectsDeletingAnEmploymentContractRegardlessOfItsStatus() {
        when(documentMapper.lockOwnDocument(DOCUMENT_ID, 1L)).thenReturn(
                DocumentOwnershipRow.builder().documentType("EMPLOYMENT_CONTRACT").status("ACTIVE").build());

        assertThrows(ContractRetentionRequiredException.class,
                () -> service.delete(WORKER, DOCUMENT_ID));

        verify(documentMapper, never()).deleteHealthCertificate(anyLong());
        verify(documentMapper, never()).revokeActiveShares(anyLong(), any());
    }

    @Test
    void throwsNotFoundWhenTheDocumentIsMissingOrNotOwned() {
        when(documentMapper.lockOwnDocument(DOCUMENT_ID, 1L)).thenReturn(null);

        assertThrows(DocumentNotFoundException.class, () -> service.delete(WORKER, DOCUMENT_ID));
    }
}
