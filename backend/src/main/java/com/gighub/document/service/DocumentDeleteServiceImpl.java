package com.gighub.document.service;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.document.exception.ContractRetentionRequiredException;
import com.gighub.document.exception.DocumentNotFoundException;
import com.gighub.document.mapper.ContractDocumentWriteMapper;
import com.gighub.document.mapper.result.DocumentOwnershipRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 문서 잠금 뒤 {@code documents.status=DELETED}와 모든 ACTIVE 공유의 REVOKED 전이를 한
 * Transaction에서 Commit한다(DOC-006). Version·파일·Checksum·감사는 건드리지 않는다.
 */
@Service
public class DocumentDeleteServiceImpl implements DocumentDeleteService {

    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Seoul");
    private static final String CONTRACT_TYPE = "EMPLOYMENT_CONTRACT";
    private static final String STATUS_DELETED = "DELETED";

    private final ContractDocumentWriteMapper documentMapper;
    private final Clock clock;

    @Autowired
    public DocumentDeleteServiceImpl(ContractDocumentWriteMapper documentMapper) {
        this(documentMapper, Clock.system(DATABASE_ZONE));
    }

    DocumentDeleteServiceImpl(ContractDocumentWriteMapper documentMapper, Clock clock) {
        this.documentMapper = documentMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void delete(AuthPrincipal principal, long documentId) {
        DocumentOwnershipRow row = documentMapper.lockOwnDocument(documentId, principal.getUserId());
        if (row == null) {
            throw new DocumentNotFoundException("문서를 찾을 수 없습니다.");
        }
        if (CONTRACT_TYPE.equals(row.getDocumentType())) {
            throw new ContractRetentionRequiredException("근로계약서는 삭제할 수 없습니다.");
        }
        if (STATUS_DELETED.equals(row.getStatus())) {
            // 같은 소유자의 반복 삭제는 멱등하게 204로 끝난다.
            return;
        }

        if (documentMapper.deleteHealthCertificate(documentId) != 1) {
            throw new IllegalStateException("보건증 삭제 상태 전이에 실패했습니다.");
        }
        documentMapper.revokeActiveShares(documentId, LocalDateTime.now(clock));
    }
}
