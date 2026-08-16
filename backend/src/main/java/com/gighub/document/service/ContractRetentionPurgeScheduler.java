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
import java.util.ArrayList;
import java.util.List;

/**
 * 근로계약서 보존 만료 파기 Batch입니다(DOC-012, {@code SPEC-178-05}/{@code DEC-CONTRACT-RETENTION}).
 *
 * <p>{@code work_cases.ends_at}의 서울 종료 날짜에 3년을 더한 자정이 지난 근로계약서를
 * 매일 02:00에 먼저 {@code documents.status=DELETED}로 Commit한 뒤 모든 Version의 최종·
 * 임시 Storage Object를 멱등 삭제한다. DB 전이는 {@link ContractDocumentWriteMapper#markContractDeleted}
 * 한 문장의 조건부 {@code UPDATE}로 행 잠금과 만료 재검증을 원자적으로 수행한다(SPEC-178-05
 * 1단계). 문서·Version Metadata·Checksum·서명·공유·접근감사 행은 건드리지 않는다. 한 실행은
 * {@code documentId} Keyset으로 모든 Page(각 최대 {@link #BATCH_SIZE}건)를 끝까지 순회한다
 * — 그렇지 않으면 이미 처리된 낮은 ID 문서가 매일 같은 Page를 다시 차지해 뒤에 있는 대상이
 * 영원히 굶는다.</p>
 *
 * <p>{@link ContractRetentionProperties#isPurgeEnabled()}가 {@code false}(기본)면 후보 수·
 * 대상 ID·예상 Storage 회수량만 로그로 남기는 Dry-run이다. 실제 파기는 명시적으로 켜야 한다.
 * 운영 로그는 {@code executionId}, {@code policyVersion}, {@code documentId}, {@code versionId},
 * 단계({@code stage})와 성공·실패 Enum({@code result})만 남기고 저장 Key·Checksum·당사자
 * 정보는 남기지 않는다(SPEC-178-05).</p>
 */
@Component
@RequiredArgsConstructor
public class ContractRetentionPurgeScheduler {

    static final int BATCH_SIZE = 100;
    private static final String POLICY_VERSION = "DEC-CONTRACT-RETENTION";

    private static final String STAGE_DB_STATUS_TRANSITION = "DB_STATUS_TRANSITION";
    private static final String STAGE_VERSION_LOOKUP = "VERSION_LOOKUP";
    private static final String STAGE_STORAGE_DELETE = "STORAGE_DELETE";
    private static final String RESULT_SUCCESS = "SUCCESS";
    private static final String RESULT_FAILED = "FAILED";

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

    /**
     * 실제 파기 없이 대상 {@code documentId}와 예상 Storage 회수량을 보고한다(이슈 #131:
     * "후보 수, 대상 ID, 예상 저장 용량과 차단 사유를 출력한다"). Storage 합계 조회가
     * 실패해도 대상 ID 보고는 계속한다 — 집계는 참고용 부가 정보일 뿐 Dry-run의 무변경
     * 보장과는 무관하다.
     */
    private void dryRunAllPages(String executionId) {
        long afterDocumentId = 0L;
        List<Long> documentIds = new ArrayList<>();
        long estimatedBytes = 0L;
        while (true) {
            List<ContractRetentionCandidateRow> page = fetchNextPage(executionId, afterDocumentId);
            if (page == null || page.isEmpty()) {
                break;
            }
            List<Long> pageDocumentIds =
                    page.stream().map(ContractRetentionCandidateRow::getDocumentId).toList();
            documentIds.addAll(pageDocumentIds);
            estimatedBytes += sumVersionSizeBytesSafely(executionId, pageDocumentIds);
            afterDocumentId = lastDocumentId(page);
        }
        if (!documentIds.isEmpty()) {
            log.info(
                    "[Dry-run] 근로계약서 보존 만료 파기 후보. executionId={}, policyVersion={}, "
                            + "candidates={}, documentIds={}, estimatedBytes={}",
                    executionId, POLICY_VERSION, documentIds.size(), documentIds, estimatedBytes);
        }
    }

    private long sumVersionSizeBytesSafely(String executionId, List<Long> documentIds) {
        try {
            Long sum = documentMapper.sumVersionSizeBytesByDocumentIds(documentIds);
            return sum == null ? 0L : sum;
        } catch (RuntimeException failure) {
            log.warn(
                    "근로계약서 보존 만료 예상 Storage 회수량 집계에 실패했습니다. "
                            + "executionId={}, policyVersion={}",
                    executionId, POLICY_VERSION, failure);
            return 0L;
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
                if (purgeOne(executionId, candidate)) {
                    purged++;
                } else {
                    failed++;
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

    /**
     * 한 문서를 파기한다. 실패한 문서는 상태만 DELETED로 남고 저장소 Object가 남아 있을 수
     * 있는데, 다음 실행이 같은 조건으로 다시 후보로 잡아 재시도한다(별도 실패 Flag를 두지
     * 않는다). 한 Version의 Storage 삭제 실패가 같은 문서의 다른 Version 재시도를 막지
     * 않도록 Version 단위로 계속 진행한다.
     *
     * @return 이 문서의 모든 단계가 성공했으면 {@code true}
     */
    private boolean purgeOne(String executionId, ContractRetentionCandidateRow candidate) {
        long documentId = candidate.getDocumentId();
        if (!"DELETED".equals(candidate.getStatus())
                && !transitionToDeleted(executionId, documentId)) {
            return false;
        }

        List<ContractRetentionVersionKeyRow> versions;
        try {
            versions = documentMapper.findVersionKeysByDocumentId(documentId);
        } catch (RuntimeException failure) {
            // Version 조회 실패도 이 문서 하나만 실패로 남긴다 — 다음 실행이 같은 조건으로
            // 다시 후보로 잡아 재시도한다.
            logStage(executionId, documentId, null, STAGE_VERSION_LOOKUP, RESULT_FAILED, failure);
            return false;
        }

        boolean allVersionsSucceeded = true;
        for (ContractRetentionVersionKeyRow version : versions) {
            if (!deleteVersionObjects(executionId, documentId, version)) {
                allVersionsSucceeded = false;
            }
        }
        return allVersionsSucceeded;
    }

    private boolean transitionToDeleted(String executionId, long documentId) {
        try {
            documentMapper.markContractDeleted(documentId);
        } catch (RuntimeException failure) {
            logStage(executionId, documentId, null, STAGE_DB_STATUS_TRANSITION, RESULT_FAILED, failure);
            return false;
        }
        logStage(executionId, documentId, null, STAGE_DB_STATUS_TRANSITION, RESULT_SUCCESS, null);
        return true;
    }

    private boolean deleteVersionObjects(
            String executionId, long documentId, ContractRetentionVersionKeyRow version) {
        try {
            storageAdapter.deleteFinal(version.getStorageKey());
            storageAdapter.deletePending(
                    ContractStorageKeys.pendingKey(
                            version.getWorkCaseId(), documentId, version.getVersionNo()));
        } catch (RuntimeException failure) {
            logStage(
                    executionId, documentId, version.getVersionId(),
                    STAGE_STORAGE_DELETE, RESULT_FAILED, failure);
            return false;
        }
        logStage(
                executionId, documentId, version.getVersionId(),
                STAGE_STORAGE_DELETE, RESULT_SUCCESS, null);
        return true;
    }

    private void logStage(
            String executionId,
            long documentId,
            Long versionId,
            String stage,
            String result,
            RuntimeException failure) {
        if (RESULT_FAILED.equals(result)) {
            log.warn(
                    "근로계약서 보존 만료 파기 단계가 실패했습니다. executionId={}, policyVersion={}, "
                            + "documentId={}, versionId={}, stage={}, result={}",
                    executionId, POLICY_VERSION, documentId, versionId, stage, result, failure);
        } else {
            log.info(
                    "근로계약서 보존 만료 파기 단계를 완료했습니다. executionId={}, policyVersion={}, "
                            + "documentId={}, versionId={}, stage={}, result={}",
                    executionId, POLICY_VERSION, documentId, versionId, stage, result);
        }
    }

    private static long lastDocumentId(List<ContractRetentionCandidateRow> page) {
        return page.get(page.size() - 1).getDocumentId();
    }
}
