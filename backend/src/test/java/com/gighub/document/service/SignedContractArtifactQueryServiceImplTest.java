package com.gighub.document.service;

import com.gighub.document.mapper.ContractDocumentWriteMapper;
import com.gighub.document.mapper.result.ContractVersionPromotionRow;
import com.gighub.document.storage.ContractStorageKeys;
import com.gighub.document.storage.DocumentStorageAdapter;
import com.gighub.document.storage.DocumentStorageIntegrityException;
import com.gighub.document.storage.Sha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignedContractArtifactQueryServiceImplTest {

    private static final long WORK_CASE_ID = 17L;
    private static final long DOCUMENT_ID = 23L;

    @Mock
    private ContractDocumentWriteMapper documentMapper;

    @Mock
    private DocumentStorageAdapter storageAdapter;

    @Test
    void acceptsVerifiedPendingFallbackWhenPromotionIsDelayed() {
        byte[] content = "signed-contract".getBytes(StandardCharsets.UTF_8);
        ContractVersionPromotionRow row = signedRow(2, Sha256.digest(content));
        when(documentMapper.findPromotionRowsByWorkCaseId(WORK_CASE_ID)).thenReturn(List.of(row));
        when(storageAdapter.read(row.getStorageKey()))
                .thenThrow(new DocumentStorageIntegrityException("missing"));
        when(storageAdapter.read(ContractStorageKeys.pendingKey(
                WORK_CASE_ID, DOCUMENT_ID, row.getVersionNo())))
                .thenReturn(content);

        assertTrue(queryService().isReadable(WORK_CASE_ID));
    }

    @Test
    void verifiesOnlyTheLatestSignedVersion() {
        byte[] latest = "latest-signed-contract".getBytes(StandardCharsets.UTF_8);
        ContractVersionPromotionRow oldRow = signedRow(
                2, Sha256.digest("old".getBytes(StandardCharsets.UTF_8)));
        ContractVersionPromotionRow latestRow = signedRow(3, Sha256.digest(latest));
        when(documentMapper.findPromotionRowsByWorkCaseId(WORK_CASE_ID))
                .thenReturn(List.of(oldRow, latestRow));
        when(storageAdapter.read(latestRow.getStorageKey())).thenReturn(latest);

        assertTrue(queryService().isReadable(WORK_CASE_ID));

        verify(storageAdapter, never()).read(oldRow.getStorageKey());
    }

    @Test
    void rejectsArtifactWhenFinalAndPendingChecksumsDoNotMatch() {
        ContractVersionPromotionRow row = signedRow(
                2, Sha256.digest("expected".getBytes(StandardCharsets.UTF_8)));
        when(documentMapper.findPromotionRowsByWorkCaseId(WORK_CASE_ID)).thenReturn(List.of(row));
        when(storageAdapter.read(row.getStorageKey()))
                .thenReturn("wrong-final".getBytes(StandardCharsets.UTF_8));
        when(storageAdapter.read(ContractStorageKeys.pendingKey(
                WORK_CASE_ID, DOCUMENT_ID, row.getVersionNo())))
                .thenReturn("wrong-pending".getBytes(StandardCharsets.UTF_8));

        assertFalse(queryService().isReadable(WORK_CASE_ID));
    }

    private ContractVersionPromotionRow signedRow(int versionNo, byte[] checksum) {
        return ContractVersionPromotionRow.builder()
                .documentId(DOCUMENT_ID)
                .versionNo(versionNo)
                .versionType("SIGNED")
                .storageKey("contracts/17/23/v" + versionNo + ".pdf")
                .checksum(checksum)
                .build();
    }

    private SignedContractArtifactQueryService queryService() {
        return new SignedContractArtifactQueryServiceImpl(documentMapper, storageAdapter);
    }
}
