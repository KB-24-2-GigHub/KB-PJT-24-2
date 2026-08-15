package com.gighub.document.service;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.common.exception.ValidationException;
import com.gighub.document.dto.DocumentListItem;
import com.gighub.document.dto.DocumentListItems;
import com.gighub.document.exception.DocumentNotFoundException;
import com.gighub.document.mapper.ContractDocumentWriteMapper;
import com.gighub.document.mapper.DocumentQueryMapper;
import com.gighub.document.mapper.result.DocumentListRow;
import com.gighub.member.domain.UserRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * {@code PATCH /api/documents/{documentId}}의 보건증 발급일 수정을 처리합니다
 * (DOC-006). 파일·Version은 바꾸지 않고 {@code documents.issued_on}·{@code expires_on}만
 * 갱신한다.
 */
@Service
public class HealthCertificateUpdateServiceImpl implements HealthCertificateUpdateService {

    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Seoul");

    private final ContractDocumentWriteMapper documentMapper;
    private final DocumentQueryMapper documentQueryMapper;
    private final Clock clock;

    @Autowired
    public HealthCertificateUpdateServiceImpl(
            ContractDocumentWriteMapper documentMapper, DocumentQueryMapper documentQueryMapper) {
        this(documentMapper, documentQueryMapper, Clock.system(DATABASE_ZONE));
    }

    HealthCertificateUpdateServiceImpl(
            ContractDocumentWriteMapper documentMapper,
            DocumentQueryMapper documentQueryMapper,
            Clock clock) {
        this.documentMapper = documentMapper;
        this.documentQueryMapper = documentQueryMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public DocumentListItem updateIssuedDate(
            AuthPrincipal principal, long documentId, LocalDate issuedDate) {
        requireWorkerRole(principal);
        LocalDate today = LocalDate.now(clock);
        requireNotFutureIssuedDate(issuedDate, today);

        int updated = documentMapper.updateHealthCertificateIssuedDate(
                documentId, principal.getUserId(), issuedDate, issuedDate.plusYears(1));
        if (updated != 1) {
            throw new DocumentNotFoundException("문서를 찾을 수 없습니다.");
        }

        DocumentListRow row = documentQueryMapper.findOwnHealthCertificateById(
                principal.getUserId(), documentId, today);
        if (row == null) {
            throw new IllegalStateException("방금 수정한 보건증을 다시 읽지 못했습니다.");
        }
        return DocumentListItems.from(row);
    }

    private void requireWorkerRole(AuthPrincipal principal) {
        if (principal.getRole() != UserRole.WORKER) {
            throw new RoleMismatchException("보건증 수정은 WORKER만 사용할 수 있습니다.");
        }
    }

    private void requireNotFutureIssuedDate(LocalDate issuedDate, LocalDate today) {
        if (issuedDate == null) {
            throw new ValidationException("발급일이 필요합니다.", "issuedDate", "REQUIRED");
        }
        if (issuedDate.isAfter(today)) {
            throw new ValidationException("발급일은 미래일 수 없습니다.", "issuedDate", "FUTURE_DATE");
        }
    }
}
