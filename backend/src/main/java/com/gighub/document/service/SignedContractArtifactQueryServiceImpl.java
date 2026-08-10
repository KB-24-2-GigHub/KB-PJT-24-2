package com.gighub.document.service;

import com.gighub.document.mapper.ContractDocumentWriteMapper;
import com.gighub.document.mapper.result.ContractVersionPromotionRow;
import com.gighub.document.storage.ContractStorageKeys;
import com.gighub.document.storage.DocumentStorageAdapter;
import com.gighub.document.storage.Sha256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Comparator;

/** Document persistence와 storage fallback을 한 owner 경계에서 검증합니다. */
@Service
public class SignedContractArtifactQueryServiceImpl
        implements SignedContractArtifactQueryService {

    private static final Logger log = LoggerFactory.getLogger(
            SignedContractArtifactQueryServiceImpl.class);
    private static final String SIGNED = "SIGNED";

    private final ContractDocumentWriteMapper documentMapper;
    private final DocumentStorageAdapter storageAdapter;

    public SignedContractArtifactQueryServiceImpl(
            ContractDocumentWriteMapper documentMapper,
            DocumentStorageAdapter storageAdapter) {
        this.documentMapper = documentMapper;
        this.storageAdapter = storageAdapter;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isReadable(long workCaseId) {
        return documentMapper.findPromotionRowsByWorkCaseId(workCaseId).stream()
                .filter(row -> SIGNED.equals(row.getVersionType()))
                .max(Comparator.comparing(ContractVersionPromotionRow::getVersionNo))
                .map(row -> isReadable(workCaseId, row))
                .orElse(false);
    }

    private boolean isReadable(long workCaseId, ContractVersionPromotionRow row) {
        if (matches(row.getStorageKey(), row.getChecksum())) {
            return true;
        }
        String pendingKey = ContractStorageKeys.pendingKey(
                workCaseId, row.getDocumentId(), row.getVersionNo());
        return matches(pendingKey, row.getChecksum());
    }

    private boolean matches(String storageKey, byte[] expectedChecksum) {
        try {
            return Arrays.equals(Sha256.digest(storageAdapter.read(storageKey)), expectedChecksum);
        } catch (RuntimeException failure) {
            log.debug("READY 계약 파일 검증을 다음 주기로 미룹니다.", failure);
            return false;
        }
    }
}
