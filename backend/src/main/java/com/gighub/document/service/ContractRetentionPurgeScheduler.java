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
 * 행은 건드리지 않는다. 한 실행은 {@code documentId} Keyset으로 모든 Page(각 최대
 * {@link #BATCH_SIZE}건)를 끝까지 순회한다 — 그렇지 않으면 이미 처리된 낮은 ID 문서가
 * 매일 같은 Page를 다시 차지해 뒤에 있는 대상이 영원히 굶는다(SPEC-178-05).</p>
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

        if (properties.isPurgeEnabled()) {
            purgeAllPages(executionId);
        } else {
            dryRunAllPages(executionId);
        }
    }

    private void logOrphans(String executionId) {
        long afterDocumentId = 0L;
        while (true) {
            List<Long> orphanDocumentIds;
            try {
                orphanDocumentIds =
                        documentMapper.findOrphanedContractDocumentIds(afterDocumentId, BATCH_SIZE);
            } catch (RuntimeException failure) {
                // 손상 점검 자체가 실패해도 다음 실행에서 다시 시도하면 되므로 예외를 삼킨다.
                log.warn(
                        "근로계약서 근무 참조 손상 점검에 실패했습니다. executionId={}, policyVersion={}",
                        executionId, POLICY_VERSION, failure);
                return;
            }
            if (orphanDocumentIds.isEmpty()) {
                return;
            }
            for (Long documentId : orphanDocumentIds) {
                log.error(
                        "근로계약서의 근무 참조가 없어 보존 만료 판정에서 격리했습니다. "
                                + "executionId={}, policyVersion={}, documentId={}",
                        executionId, POLICY_VERSION, documentId);
            }
            // 후보 조회와 같은 이유로 전체 Page를 순회한다 — 앞선 Page의 고아 문서가 매일
            // 같은 Page를 다시 차지해 뒤에 있는 고아 문서가 영원히 로그에 잡히지 않는 것을
            // 막는다.
            afterDocumentId = orphanDocumentIds.get(orphanDocumentIds.size() - 1);
        }
    }

    private void dryRunAllPages(String executionId) {
        long afterDocumentId = 0L;
        int total = 0;
        while (true) {
            List<ContractRetentionCandidateRow> page = fetchNextPage(executionId, afterDocumentId);
            if (page == null || page.isEmpty()) {
                break;
            }
            total += page.size();
            afterDocumentId = lastDocumentId(page);
        }
        if (total > 0) {
            log.info(
                    "[Dry-run] 근로계약서 보존 만료 파기 후보. executionId={}, policyVersion={}, "
                            + "candidates={}",
                    executionId, POLICY_VERSION, total);
        }
    }

    private void purgeAllPages(String executionId) {
        long afterDocumentId = 0L;
        int totalCandidates = 0;
        int purged = 0;
        int failed = 0;
        while (true) {
            List<ContractRetentionCandidateRow> page = fetchNextPage(executionId, afterDocumentId);
            if (page == null || page.isEmpty()) {
                break;
            }
            totalCandidates += page.size();
            for (ContractRetentionCandidateRow candidate : page) {
                try {
                    purgeOne(candidate);
                    purged++;
                } catch (RuntimeException failure) {
                    failed++;
                    // 한 문서의 파기 실패가 다른 문서 처리를 막지 않는다. 실패한 문서는 상태만
                    // DELETED로 남고 저장소 Object가 남아 있을 수 있는데, 다음 실행이 같은
                    // 조건으로 다시 후보로 잡아 재시도한다(별도 실패 Flag를 두지 않는다).
                    log.warn(
                            "근로계약서 보존 만료 파기에 실패했습니다. executionId={}, "
                                    + "policyVersion={}, documentId={}",
                            executionId, POLICY_VERSION, candidate.getDocumentId(), failure);
                }
            }
            afterDocumentId = lastDocumentId(page);
        }
        if (totalCandidates > 0) {
            log.info(
                    "근로계약서 보존 만료 파기 실행을 완료했습니다. executionId={}, policyVersion={}, "
                            + "candidates={}, purged={}, failed={}",
                    executionId, POLICY_VERSION, totalCandidates, purged, failed);
        }
    }

    /** 후보 한 Page를 조회한다. 조회 자체가 실패하면 {@code null}을 돌려줘 이번 실행을 멈춘다. */
    private List<ContractRetentionCandidateRow> fetchNextPage(String executionId, long afterDocumentId) {
        try {
            return documentMapper.findContractRetentionCandidates(afterDocumentId, BATCH_SIZE);
        } catch (RuntimeException failure) {
            // 후보 조회 자체가 실패해도 다음 실행에서 다시 시도하면 되므로 예외를 삼킨다.
            log.warn(
                    "근로계약서 보존 만료 후보 조회에 실패했습니다. executionId={}, policyVersion={}",
                    executionId, POLICY_VERSION, failure);
            return null;
        }
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

    private static long lastDocumentId(List<ContractRetentionCandidateRow> page) {
        return page.get(page.size() - 1).getDocumentId();
    }
}
