package com.gighub.document.service;

import com.gighub.document.config.ContractRetentionProperties;
import com.gighub.document.mapper.ContractDocumentWriteMapper;
import com.gighub.document.mapper.result.ContractRetentionCandidateRow;
import com.gighub.document.mapper.result.ContractRetentionVersionKeyRow;
import com.gighub.document.storage.ContractStorageKeys;
import com.gighub.document.storage.DocumentStorageAdapter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 근로계약서 보존 만료 파기 Batch입니다(DOC-012, {@code DEC-CONTRACT-RETENTION}).
 *
 * <p>{@code work_cases.ends_at}의 서울 종료 날짜에 3년을 더한 자정이 지난 근로계약서를
 * 매일 02:00에 먼저 {@code documents.status=DELETED}로 Commit한 뒤 모든 Version의 최종·
 * 임시 Storage Object를 멱등 삭제한다. 문서·Version Metadata·Checksum·서명·공유·접근감사
 * 행은 건드리지 않는다. 한 실행은 한 페이지(최대 {@link #BATCH_SIZE}건)만 처리하며, 초과
 * 후보는 다음 날 실행이 자연히 이어받는다 — 이 프로젝트 규모에서 한 실행 안의 다중 페이지
 * 순회는 불필요한 복잡도다.</p>
 *
 * <p>{@link ContractRetentionProperties#isPurgeEnabled()}가 {@code false}(기본)면 후보
 * 집계만 로그로 남기는 Dry-run이다. 실제 파기는 명시적으로 켜야 한다.</p>
 */
@Component
@RequiredArgsConstructor
public class ContractRetentionPurgeScheduler {

    static final int BATCH_SIZE = 100;
    private static final String POLICY_VERSION = "DEC-CONTRACT-RETENTION";

    private static final Logger log = LoggerFactory.getLogger(ContractRetentionPurgeScheduler.class);

    private final ContractDocumentWriteMapper documentMapper;
    private final DocumentStorageAdapter storageAdapter;
    private final ContractRetentionProperties properties;

    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Seoul")
    public void runOnce() {
        String executionId = Instant.now().toString();
        logOrphans(executionId);

        List<ContractRetentionCandidateRow> candidates;
        try {
            candidates = documentMapper.findContractRetentionCandidates(BATCH_SIZE);
        } catch (RuntimeException failure) {
            // 후보 조회 자체가 실패해도 다음 실행에서 다시 시도하면 되므로 예외를 삼킨다.
            log.warn(
                    "근로계약서 보존 만료 후보 조회에 실패했습니다. executionId={}, policyVersion={}",
                    executionId, POLICY_VERSION, failure);
            return;
        }
        if (candidates.isEmpty()) {
            return;
        }

        if (!properties.isPurgeEnabled()) {
            log.info(
                    "[Dry-run] 근로계약서 보존 만료 파기 후보. executionId={}, policyVersion={}, "
                            + "candidates={}, documentIds={}",
                    executionId, POLICY_VERSION, candidates.size(), documentIds(candidates));
            return;
        }

        processCandidates(executionId, candidates);
    }

    private void logOrphans(String executionId) {
        try {
            List<Long> orphanDocumentIds =
                    documentMapper.findOrphanedContractDocumentIds(BATCH_SIZE);
            for (Long documentId : orphanDocumentIds) {
                log.error(
                        "근로계약서의 근무 참조가 없어 보존 만료 판정에서 격리했습니다. "
                                + "executionId={}, documentId={}",
                        executionId, documentId);
            }
        } catch (RuntimeException failure) {
            log.warn("근로계약서 근무 참조 손상 점검에 실패했습니다. executionId={}", executionId, failure);
        }
    }

    private void processCandidates(String executionId, List<ContractRetentionCandidateRow> candidates) {
        int purged = 0;
        int failed = 0;
        for (ContractRetentionCandidateRow candidate : candidates) {
            try {
                purgeOne(candidate);
                purged++;
            } catch (RuntimeException failure) {
                failed++;
                // 한 문서의 파기 실패가 다른 문서 처리를 막지 않는다. 실패한 문서는 상태만
                // DELETED로 남고 저장소 Object가 남아 있을 수 있는데, 다음 실행이 같은 조건으로
                // 다시 후보로 잡아 재시도한다(별도 실패 Flag를 두지 않는다).
                log.warn(
                        "근로계약서 보존 만료 파기에 실패했습니다. executionId={}, documentId={}",
                        executionId, candidate.getDocumentId(), failure);
            }
        }
        log.info(
                "근로계약서 보존 만료 파기 실행을 완료했습니다. executionId={}, policyVersion={}, "
                        + "candidates={}, purged={}, failed={}",
                executionId, POLICY_VERSION, candidates.size(), purged, failed);
    }

    private void purgeOne(ContractRetentionCandidateRow candidate) {
        long documentId = candidate.getDocumentId();
        if (!"DELETED".equals(candidate.getStatus())) {
            documentMapper.markContractDeleted(documentId);
        }
        for (ContractRetentionVersionKeyRow version :
                documentMapper.findVersionKeysByDocumentId(documentId)) {
            storageAdapter.deleteFinal(version.getStorageKey());
            storageAdapter.deletePending(
                    ContractStorageKeys.pendingKey(
                            version.getWorkCaseId(), documentId, version.getVersionNo()));
        }
    }

    private static List<Long> documentIds(List<ContractRetentionCandidateRow> candidates) {
        return candidates.stream().map(ContractRetentionCandidateRow::getDocumentId).toList();
    }
}
