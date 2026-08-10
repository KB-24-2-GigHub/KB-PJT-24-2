package com.gighub.document.contract.impl;

import com.gighub.contract.ContractArtifactCommand;
import com.gighub.contract.ContractArtifactHandle;
import com.gighub.contract.domain.AcceptedContract;
import com.gighub.contract.domain.ContractTermsSnapshot;
import com.gighub.document.contract.ContractPdfRenderer;
import com.gighub.document.contract.ContractSnapshot;
import com.gighub.document.mapper.ContractDocumentWriteMapper;
import com.gighub.document.mapper.param.DocumentInsertParam;
import com.gighub.document.mapper.param.DocumentShareInsertParam;
import com.gighub.document.mapper.param.DocumentSignatureInsertParam;
import com.gighub.document.mapper.param.DocumentVersionInsertParam;
import com.gighub.document.mapper.result.ContractVersionPromotionRow;
import com.gighub.document.storage.ContractStorageKeys;
import com.gighub.document.storage.DocumentStorageAdapter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WORKER 수락이 ORIGINAL·SIGNED 문서 행과 임시 파일을 만들고, Commit 뒤 승격·실패 뒤 정리가
 * 실패해도 던지지 않는지 확인합니다.
 */
class PdfContractArtifactPortTest {

    private static final long WORK_CASE_ID = 501L;
    private static final long CONTRACT_ID = 900L;
    private static final long OWNER_ID = 1L;
    private static final long WORKER_ID = 2L;
    private static final LocalDateTime ACCEPTED_AT = LocalDateTime.of(2026, 8, 7, 10, 0);

    private final ContractDocumentWriteMapper documentMapper = mock(ContractDocumentWriteMapper.class);
    private final ContractPdfRenderer renderer = mock(ContractPdfRenderer.class);
    private final DocumentStorageAdapter storageAdapter = mock(DocumentStorageAdapter.class);

    private final PdfContractArtifactPort port = new PdfContractArtifactPort(
            documentMapper, renderer, storageAdapter);

    @Test
    void prepareWritesBothVersionsAndSharesWithTheWorker() {
        when(renderer.render(org.mockito.ArgumentMatchers.any(ContractSnapshot.class)))
                .thenReturn("original".getBytes());
        when(renderer.render(
                org.mockito.ArgumentMatchers.any(ContractSnapshot.class),
                org.mockito.ArgumentMatchers.any(ContractSnapshot.Signature.class)))
                .thenReturn("signed".getBytes());
        stubGeneratedIds();

        ContractArtifactHandle handle = port.prepare(
                ContractArtifactCommand.from(AcceptedContract.of(
                        WORK_CASE_ID, CONTRACT_ID, ACCEPTED_AT, terms())));

        assertEquals(WORK_CASE_ID, handle.getWorkCaseId());
        assertEquals(CONTRACT_ID, handle.getContractId());

        ArgumentCaptor<DocumentInsertParam> documentCaptor =
                ArgumentCaptor.forClass(DocumentInsertParam.class);
        verify(documentMapper).insertDocument(documentCaptor.capture());
        assertEquals(OWNER_ID, documentCaptor.getValue().getOwnerUserId());
        assertEquals(WORK_CASE_ID, documentCaptor.getValue().getWorkCaseId());
        assertEquals("AWAITING_SIGNATURE", documentCaptor.getValue().getStatus());

        verify(documentMapper, times(2)).insertVersion(
                org.mockito.ArgumentMatchers.any(DocumentVersionInsertParam.class));

        ArgumentCaptor<DocumentSignatureInsertParam> signatureCaptor =
                ArgumentCaptor.forClass(DocumentSignatureInsertParam.class);
        verify(documentMapper).insertSignature(signatureCaptor.capture());
        assertEquals(WORKER_ID, signatureCaptor.getValue().getSignerUserId());
        assertEquals("근로자", signatureCaptor.getValue().getTypedName());

        ArgumentCaptor<DocumentShareInsertParam> shareCaptor =
                ArgumentCaptor.forClass(DocumentShareInsertParam.class);
        verify(documentMapper).insertShare(shareCaptor.capture());
        assertEquals(WORKER_ID, shareCaptor.getValue().getSharedWithUserId());
        assertEquals("CONTRACT_PARTY", shareCaptor.getValue().getPurpose());

        verify(storageAdapter).writePending(
                eq(ContractStorageKeys.pendingKey(WORK_CASE_ID, 9L, 1)),
                eq("original".getBytes()));
        verify(storageAdapter).writePending(
                eq(ContractStorageKeys.pendingKey(WORK_CASE_ID, 9L, 2)),
                eq("signed".getBytes()));
        verify(documentMapper).updateDocumentStatus(9L, "AWAITING_SIGNATURE", "SIGNED");
        verify(documentMapper).updateDocumentStatus(9L, "SIGNED", "ACTIVE");
    }

    @Test
    void promotesEachVersionFromItsDeterministicPendingKey() {
        when(documentMapper.findPromotionRowsByWorkCaseId(WORK_CASE_ID)).thenReturn(List.of(
                ContractVersionPromotionRow.builder()
                        .documentId(9L).versionNo(1)
                        .versionType("ORIGINAL").storageKey("contracts/501/9/v1.pdf")
                        .checksum(new byte[]{1}).build(),
                ContractVersionPromotionRow.builder()
                        .documentId(9L).versionNo(2)
                        .versionType("SIGNED").storageKey("contracts/501/9/v2.pdf")
                        .checksum(new byte[]{2}).build()));

        port.promote(ContractArtifactHandle.of(WORK_CASE_ID, CONTRACT_ID));

        verify(storageAdapter).promote(
                ContractStorageKeys.pendingKey(WORK_CASE_ID, 9L, 1),
                "contracts/501/9/v1.pdf", new byte[]{1});
        verify(storageAdapter).promote(
                ContractStorageKeys.pendingKey(WORK_CASE_ID, 9L, 2),
                "contracts/501/9/v2.pdf", new byte[]{2});
    }

    @Test
    void promoteNeverThrowsWhenStorageFails() {
        when(documentMapper.findPromotionRowsByWorkCaseId(WORK_CASE_ID)).thenReturn(List.of(
                ContractVersionPromotionRow.builder()
                        .documentId(9L).versionNo(1)
                        .versionType("ORIGINAL").storageKey("contracts/501/9/v1.pdf")
                        .checksum(new byte[]{1}).build()));
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(storageAdapter).promote(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());

        port.promote(ContractArtifactHandle.of(WORK_CASE_ID, CONTRACT_ID));
    }

    @Test
    void promoteIsANoOpWhenThereIsNothingToPromote() {
        port.promote(ContractArtifactHandle.nothing());

        verify(documentMapper, never()).findPromotionRowsByWorkCaseId(anyLong());
    }

    @Test
    void discardPendingCleansUpBothFilesAndNeverThrows() {
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(storageAdapter)
                .deletePendingByWorkCaseId(WORK_CASE_ID, Set.of());

        port.discardPending(WORK_CASE_ID);

        verify(storageAdapter).deletePendingByWorkCaseId(WORK_CASE_ID, Set.of());
    }

    @Test
    void rollbackCleanupPreservesPendingFallbackForACommittedConcurrentWinner() {
        when(documentMapper.findPromotionRowsByWorkCaseId(WORK_CASE_ID)).thenReturn(List.of(
                ContractVersionPromotionRow.builder()
                        .documentId(77L).versionNo(2).versionType("SIGNED")
                        .storageKey("contracts/501/77/v2.pdf")
                        .checksum(new byte[]{1}).build()));

        port.discardPending(WORK_CASE_ID);

        verify(storageAdapter).deletePendingByWorkCaseId(WORK_CASE_ID, Set.of(77L));
    }

    private ContractTermsSnapshot terms() {
        return ContractTermsSnapshot.builder()
                .termsVersion(1)
                .title("주말 홀 서빙")
                .startsAt(Instant.parse("2026-08-07T01:00:00Z"))
                .endsAt(Instant.parse("2026-08-07T05:00:00Z"))
                .breakMinutes(30)
                .breakPaid(false)
                .workplaceName("강남점")
                .workplaceAddress("서울시 강남구")
                .dailyWage(120_000L)
                .owner(OWNER_ID, "사장")
                .worker(WORKER_ID, "근로자")
                .build();
    }

    private void stubGeneratedIds() {
        org.mockito.Mockito.doAnswer(invocation -> {
            DocumentInsertParam param = invocation.getArgument(0);
            param.setId(9L);
            return 1;
        }).when(documentMapper).insertDocument(org.mockito.ArgumentMatchers.any());

        org.mockito.Mockito.doAnswer(invocation -> {
            DocumentVersionInsertParam param = invocation.getArgument(0);
            param.setId(param.getVersionNo() == 1 ? 91L : 92L);
            return 1;
        }).when(documentMapper).insertVersion(org.mockito.ArgumentMatchers.any());

        when(documentMapper.insertSignature(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(documentMapper.insertShare(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(documentMapper.updateDocumentStatus(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
    }
}
