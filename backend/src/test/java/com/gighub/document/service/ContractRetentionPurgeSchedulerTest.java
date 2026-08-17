package com.gighub.document.service;

import com.gighub.document.config.ContractRetentionProperties;
import com.gighub.document.mapper.ContractDocumentWriteMapper;
import com.gighub.document.mapper.result.ContractRetentionCandidateRow;
import com.gighub.document.mapper.result.ContractRetentionVersionKeyRow;
import com.gighub.document.storage.ContractStorageKeys;
import com.gighub.document.storage.DocumentStorageAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static com.gighub.document.service.ContractRetentionPurgeScheduler.BATCH_SIZE;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContractRetentionPurgeSchedulerTest {

    private static final long DOCUMENT_ID = 42L;
    private static final long WORK_CASE_ID = 7L;

    @Mock
    private ContractDocumentWriteMapper documentMapper;

    @Mock
    private DocumentStorageAdapter storageAdapter;

    @Test
    void dryRunLeavesTheDatabaseAndStorageUntouched() {
        when(documentMapper.findOrphanedContractDocumentIds(0L, BATCH_SIZE)).thenReturn(List.of());
        when(documentMapper.findContractRetentionCandidates(0L, BATCH_SIZE)).thenReturn(List.of(
                ContractRetentionCandidateRow.builder().documentId(DOCUMENT_ID).status("ACTIVE").build()));
        when(documentMapper.findContractRetentionCandidates(DOCUMENT_ID, BATCH_SIZE))
                .thenReturn(List.of());
        ContractRetentionPurgeScheduler scheduler = new ContractRetentionPurgeScheduler(
                documentMapper, storageAdapter, new ContractRetentionProperties(new MockEnvironment()));

        scheduler.runOnce();

        verify(documentMapper, never()).markContractDeleted(anyLong());
        verify(documentMapper, never()).findVersionKeysByDocumentId(anyLong());
        verify(storageAdapter, never()).deleteFinal(anyString());
    }

    /** 이슈 #131: Dry-run은 후보 수뿐 아니라 대상 ID와 예상 Storage 회수량도 보고한다. */
    @Test
    void dryRunReportsCandidateIdsAndEstimatedStorageBytes() {
        when(documentMapper.findOrphanedContractDocumentIds(0L, BATCH_SIZE)).thenReturn(List.of());
        when(documentMapper.findContractRetentionCandidates(0L, BATCH_SIZE)).thenReturn(List.of(
                ContractRetentionCandidateRow.builder().documentId(DOCUMENT_ID).status("ACTIVE").build()));
        when(documentMapper.findContractRetentionCandidates(DOCUMENT_ID, BATCH_SIZE))
                .thenReturn(List.of());
        when(documentMapper.sumVersionSizeBytesByDocumentIds(List.of(DOCUMENT_ID))).thenReturn(2048L);
        ContractRetentionPurgeScheduler scheduler = new ContractRetentionPurgeScheduler(
                documentMapper, storageAdapter, new ContractRetentionProperties(new MockEnvironment()));

        scheduler.runOnce();

        verify(documentMapper).sumVersionSizeBytesByDocumentIds(List.of(DOCUMENT_ID));
        verify(documentMapper, never()).markContractDeleted(anyLong());
    }

    /** SPEC-178-05: 한 Version의 Storage 삭제 실패가 같은 문서의 다른 Version을 막지 않는다. */
    @Test
    void aFailingVersionDoesNotStopOtherVersionsOfTheSameDocument() {
        when(documentMapper.findOrphanedContractDocumentIds(0L, BATCH_SIZE)).thenReturn(List.of());
        when(documentMapper.findContractRetentionCandidates(0L, BATCH_SIZE)).thenReturn(List.of(
                ContractRetentionCandidateRow.builder().documentId(DOCUMENT_ID).status("ACTIVE").build()));
        when(documentMapper.findContractRetentionCandidates(DOCUMENT_ID, BATCH_SIZE))
                .thenReturn(List.of());
        when(documentMapper.markContractDeleted(DOCUMENT_ID)).thenReturn(1);
        when(documentMapper.findVersionKeysByDocumentId(DOCUMENT_ID)).thenReturn(List.of(
                ContractRetentionVersionKeyRow.builder()
                        .versionId(1L).workCaseId(WORK_CASE_ID).versionNo(1)
                        .storageKey("contracts/7/42/v1.pdf").build(),
                ContractRetentionVersionKeyRow.builder()
                        .versionId(2L).workCaseId(WORK_CASE_ID).versionNo(2)
                        .storageKey("contracts/7/42/v2.pdf").build()));
        doThrow(new RuntimeException("storage failure"))
                .when(storageAdapter).deleteFinal("contracts/7/42/v1.pdf");
        ContractRetentionPurgeScheduler scheduler = new ContractRetentionPurgeScheduler(
                documentMapper, storageAdapter, purgeEnabledProperties());

        scheduler.runOnce();

        verify(storageAdapter).deleteFinal("contracts/7/42/v1.pdf");
        verify(storageAdapter).deleteFinal("contracts/7/42/v2.pdf");
        verify(storageAdapter).deletePending(ContractStorageKeys.pendingKey(WORK_CASE_ID, DOCUMENT_ID, 2));
        verify(storageAdapter, never())
                .deletePending(ContractStorageKeys.pendingKey(WORK_CASE_ID, DOCUMENT_ID, 1));
    }

    @Test
    void purgesAnExpiredContractsStatusAndAllVersionObjects() {
        when(documentMapper.findOrphanedContractDocumentIds(0L, BATCH_SIZE)).thenReturn(List.of());
        when(documentMapper.findContractRetentionCandidates(0L, BATCH_SIZE)).thenReturn(List.of(
                ContractRetentionCandidateRow.builder().documentId(DOCUMENT_ID).status("ACTIVE").build()));
        when(documentMapper.findContractRetentionCandidates(DOCUMENT_ID, BATCH_SIZE))
                .thenReturn(List.of());
        when(documentMapper.markContractDeleted(DOCUMENT_ID)).thenReturn(1);
        when(documentMapper.findVersionKeysByDocumentId(DOCUMENT_ID)).thenReturn(List.of(
                ContractRetentionVersionKeyRow.builder()
                        .versionId(1L).workCaseId(WORK_CASE_ID).versionNo(1)
                        .storageKey("contracts/7/42/v1.pdf").build(),
                ContractRetentionVersionKeyRow.builder()
                        .versionId(2L).workCaseId(WORK_CASE_ID).versionNo(2)
                        .storageKey("contracts/7/42/v2.pdf").build()));
        ContractRetentionPurgeScheduler scheduler = new ContractRetentionPurgeScheduler(
                documentMapper, storageAdapter, purgeEnabledProperties());

        scheduler.runOnce();

        verify(documentMapper).markContractDeleted(DOCUMENT_ID);
        verify(storageAdapter).deleteFinal("contracts/7/42/v1.pdf");
        verify(storageAdapter).deleteFinal("contracts/7/42/v2.pdf");
        verify(storageAdapter).deletePending(ContractStorageKeys.pendingKey(WORK_CASE_ID, DOCUMENT_ID, 1));
        verify(storageAdapter).deletePending(ContractStorageKeys.pendingKey(WORK_CASE_ID, DOCUMENT_ID, 2));
    }

    /**
     * markContractDeleted가 재검증한 만료 조건이 후보 조회 이후 더 이상 성립하지 않으면
     * (예: 취소되거나 ends_at이 바뀜) 영향 행이 0이다 — 이 문서가 지금 확실히 DELETED라고
     * 볼 수 없으므로 Version 조회와 Storage 삭제로 진행하면 안 된다.
     */
    @Test
    void aZeroRowUpdateStopsBeforeVersionAndStorageDeletion() {
        when(documentMapper.findOrphanedContractDocumentIds(0L, BATCH_SIZE)).thenReturn(List.of());
        when(documentMapper.findContractRetentionCandidates(0L, BATCH_SIZE)).thenReturn(List.of(
                ContractRetentionCandidateRow.builder().documentId(DOCUMENT_ID).status("ACTIVE").build()));
        when(documentMapper.findContractRetentionCandidates(DOCUMENT_ID, BATCH_SIZE))
                .thenReturn(List.of());
        when(documentMapper.markContractDeleted(DOCUMENT_ID)).thenReturn(0);
        ContractRetentionPurgeScheduler scheduler = new ContractRetentionPurgeScheduler(
                documentMapper, storageAdapter, purgeEnabledProperties());

        scheduler.runOnce();

        verify(documentMapper).markContractDeleted(DOCUMENT_ID);
        verify(documentMapper, never()).findVersionKeysByDocumentId(anyLong());
        verify(storageAdapter, never()).deleteFinal(anyString());
        verify(storageAdapter, never()).deletePending(anyString());
    }

    @Test
    void alreadyDeletedCandidatesSkipTheStatusTransitionButRetryObjectDeletion() {
        when(documentMapper.findOrphanedContractDocumentIds(0L, BATCH_SIZE)).thenReturn(List.of());
        when(documentMapper.findContractRetentionCandidates(0L, BATCH_SIZE)).thenReturn(List.of(
                ContractRetentionCandidateRow.builder().documentId(DOCUMENT_ID).status("DELETED").build()));
        when(documentMapper.findContractRetentionCandidates(DOCUMENT_ID, BATCH_SIZE))
                .thenReturn(List.of());
        when(documentMapper.findVersionKeysByDocumentId(DOCUMENT_ID)).thenReturn(List.of(
                ContractRetentionVersionKeyRow.builder()
                        .workCaseId(WORK_CASE_ID).versionNo(1).storageKey("contracts/7/42/v1.pdf").build()));
        ContractRetentionPurgeScheduler scheduler = new ContractRetentionPurgeScheduler(
                documentMapper, storageAdapter, purgeEnabledProperties());

        scheduler.runOnce();

        verify(documentMapper, never()).markContractDeleted(anyLong());
        verify(storageAdapter).deleteFinal("contracts/7/42/v1.pdf");
    }

    @Test
    void aFailingCandidateDoesNotStopTheRestOfTheBatch() {
        long otherDocumentId = 99L;
        when(documentMapper.findOrphanedContractDocumentIds(0L, BATCH_SIZE)).thenReturn(List.of());
        when(documentMapper.findContractRetentionCandidates(0L, BATCH_SIZE)).thenReturn(List.of(
                ContractRetentionCandidateRow.builder().documentId(DOCUMENT_ID).status("ACTIVE").build(),
                ContractRetentionCandidateRow.builder().documentId(otherDocumentId).status("ACTIVE").build()));
        when(documentMapper.findContractRetentionCandidates(otherDocumentId, BATCH_SIZE))
                .thenReturn(List.of());
        when(documentMapper.markContractDeleted(DOCUMENT_ID)).thenReturn(1);
        when(documentMapper.markContractDeleted(otherDocumentId)).thenReturn(1);
        when(documentMapper.findVersionKeysByDocumentId(DOCUMENT_ID))
                .thenThrow(new RuntimeException("storage lookup failed"));
        when(documentMapper.findVersionKeysByDocumentId(otherDocumentId)).thenReturn(List.of(
                ContractRetentionVersionKeyRow.builder()
                        .workCaseId(WORK_CASE_ID).versionNo(1).storageKey("contracts/7/99/v1.pdf").build()));
        ContractRetentionPurgeScheduler scheduler = new ContractRetentionPurgeScheduler(
                documentMapper, storageAdapter, purgeEnabledProperties());

        scheduler.runOnce();

        verify(documentMapper).markContractDeleted(otherDocumentId);
        verify(storageAdapter).deleteFinal("contracts/7/99/v1.pdf");
    }

    @Test
    void anOrphanedDocumentIsLoggedAndNotTouched() {
        when(documentMapper.findOrphanedContractDocumentIds(0L, BATCH_SIZE)).thenReturn(List.of(DOCUMENT_ID));
        when(documentMapper.findContractRetentionCandidates(0L, BATCH_SIZE)).thenReturn(List.of());
        ContractRetentionPurgeScheduler scheduler = new ContractRetentionPurgeScheduler(
                documentMapper, storageAdapter, purgeEnabledProperties());

        scheduler.runOnce();

        verify(documentMapper, never()).markContractDeleted(anyLong());
        verify(storageAdapter, never()).deleteFinal(anyString());
    }

    @Test
    void aFullFirstOrphanPageMovesOnToTheNextPageInTheSameRun() {
        long firstPageLastId = BATCH_SIZE;
        long secondPageOrphanId = BATCH_SIZE + 1L;
        List<Long> firstPage = java.util.stream.LongStream.rangeClosed(1, BATCH_SIZE).boxed().toList();
        when(documentMapper.findOrphanedContractDocumentIds(0L, BATCH_SIZE)).thenReturn(firstPage);
        when(documentMapper.findOrphanedContractDocumentIds(firstPageLastId, BATCH_SIZE))
                .thenReturn(List.of(secondPageOrphanId));
        when(documentMapper.findOrphanedContractDocumentIds(secondPageOrphanId, BATCH_SIZE))
                .thenReturn(List.of());
        when(documentMapper.findContractRetentionCandidates(0L, BATCH_SIZE)).thenReturn(List.of());
        ContractRetentionPurgeScheduler scheduler = new ContractRetentionPurgeScheduler(
                documentMapper, storageAdapter, purgeEnabledProperties());

        scheduler.runOnce();

        // 첫 Page가 BATCH_SIZE로 꽉 차도 같은 실행이 이어서 두 번째 Page의 고아 문서까지 조회한다.
        verify(documentMapper, times(3))
                .findOrphanedContractDocumentIds(anyLong(), org.mockito.ArgumentMatchers.eq(BATCH_SIZE));
    }

    @Test
    void aFullFirstPageMovesOnToTheNextPageInTheSameRun() {
        long firstPageLastId = BATCH_SIZE;
        long secondPageDocumentId = BATCH_SIZE + 1L;
        List<ContractRetentionCandidateRow> firstPage = java.util.stream.LongStream
                .rangeClosed(1, BATCH_SIZE)
                .mapToObj(id -> ContractRetentionCandidateRow.builder()
                        .documentId(id).status("DELETED").build())
                .toList();
        when(documentMapper.findOrphanedContractDocumentIds(0L, BATCH_SIZE)).thenReturn(List.of());
        when(documentMapper.findContractRetentionCandidates(0L, BATCH_SIZE)).thenReturn(firstPage);
        when(documentMapper.findContractRetentionCandidates(firstPageLastId, BATCH_SIZE)).thenReturn(List.of(
                ContractRetentionCandidateRow.builder()
                        .documentId(secondPageDocumentId).status("ACTIVE").build()));
        when(documentMapper.findContractRetentionCandidates(secondPageDocumentId, BATCH_SIZE))
                .thenReturn(List.of());
        when(documentMapper.markContractDeleted(secondPageDocumentId)).thenReturn(1);
        ContractRetentionPurgeScheduler scheduler = new ContractRetentionPurgeScheduler(
                documentMapper, storageAdapter, purgeEnabledProperties());

        scheduler.runOnce();

        // 첫 Page가 BATCH_SIZE로 꽉 차도 같은 실행이 이어서 두 번째 Page의 문서까지 처리한다
        // (SPEC-178-05 "Job은 모든 Keyset Page를 순회한다").
        verify(documentMapper).markContractDeleted(secondPageDocumentId);
        verify(documentMapper, times(3))
                .findContractRetentionCandidates(anyLong(), org.mockito.ArgumentMatchers.eq(BATCH_SIZE));
    }

    private static ContractRetentionProperties purgeEnabledProperties() {
        return new ContractRetentionProperties(
                new MockEnvironment().withProperty(ContractRetentionProperties.PURGE_ENABLED_KEY, "true"));
    }
}
