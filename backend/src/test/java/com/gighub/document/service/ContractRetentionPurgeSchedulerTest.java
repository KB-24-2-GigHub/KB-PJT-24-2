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

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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
        when(documentMapper.findOrphanedContractDocumentIds(100)).thenReturn(List.of());
        when(documentMapper.findContractRetentionCandidates(100)).thenReturn(List.of(
                ContractRetentionCandidateRow.builder().documentId(DOCUMENT_ID).status("ACTIVE").build()));
        ContractRetentionPurgeScheduler scheduler = new ContractRetentionPurgeScheduler(
                documentMapper, storageAdapter, new ContractRetentionProperties(new MockEnvironment()));

        scheduler.runOnce();

        verify(documentMapper, never()).markContractDeleted(anyLong());
        verify(documentMapper, never()).findVersionKeysByDocumentId(anyLong());
        verify(storageAdapter, never()).deleteFinal(anyString());
    }

    @Test
    void purgesAnExpiredContractsStatusAndAllVersionObjects() {
        when(documentMapper.findOrphanedContractDocumentIds(100)).thenReturn(List.of());
        when(documentMapper.findContractRetentionCandidates(100)).thenReturn(List.of(
                ContractRetentionCandidateRow.builder().documentId(DOCUMENT_ID).status("ACTIVE").build()));
        when(documentMapper.findVersionKeysByDocumentId(DOCUMENT_ID)).thenReturn(List.of(
                ContractRetentionVersionKeyRow.builder()
                        .workCaseId(WORK_CASE_ID).versionNo(1).storageKey("contracts/7/42/v1.pdf").build(),
                ContractRetentionVersionKeyRow.builder()
                        .workCaseId(WORK_CASE_ID).versionNo(2).storageKey("contracts/7/42/v2.pdf").build()));
        ContractRetentionPurgeScheduler scheduler = new ContractRetentionPurgeScheduler(
                documentMapper, storageAdapter, purgeEnabledProperties());

        scheduler.runOnce();

        verify(documentMapper).markContractDeleted(DOCUMENT_ID);
        verify(storageAdapter).deleteFinal("contracts/7/42/v1.pdf");
        verify(storageAdapter).deleteFinal("contracts/7/42/v2.pdf");
        verify(storageAdapter).deletePending(ContractStorageKeys.pendingKey(WORK_CASE_ID, DOCUMENT_ID, 1));
        verify(storageAdapter).deletePending(ContractStorageKeys.pendingKey(WORK_CASE_ID, DOCUMENT_ID, 2));
    }

    @Test
    void alreadyDeletedCandidatesSkipTheStatusTransitionButRetryObjectDeletion() {
        when(documentMapper.findOrphanedContractDocumentIds(100)).thenReturn(List.of());
        when(documentMapper.findContractRetentionCandidates(100)).thenReturn(List.of(
                ContractRetentionCandidateRow.builder().documentId(DOCUMENT_ID).status("DELETED").build()));
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
        when(documentMapper.findOrphanedContractDocumentIds(100)).thenReturn(List.of());
        when(documentMapper.findContractRetentionCandidates(100)).thenReturn(List.of(
                ContractRetentionCandidateRow.builder().documentId(DOCUMENT_ID).status("ACTIVE").build(),
                ContractRetentionCandidateRow.builder().documentId(otherDocumentId).status("ACTIVE").build()));
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
        when(documentMapper.findOrphanedContractDocumentIds(100)).thenReturn(List.of(DOCUMENT_ID));
        when(documentMapper.findContractRetentionCandidates(100)).thenReturn(List.of());
        ContractRetentionPurgeScheduler scheduler = new ContractRetentionPurgeScheduler(
                documentMapper, storageAdapter, purgeEnabledProperties());

        scheduler.runOnce();

        verify(documentMapper, never()).markContractDeleted(anyLong());
        verify(storageAdapter, never()).deleteFinal(anyString());
    }

    private static ContractRetentionProperties purgeEnabledProperties() {
        return new ContractRetentionProperties(
                new MockEnvironment().withProperty(ContractRetentionProperties.PURGE_ENABLED_KEY, "true"));
    }
}
