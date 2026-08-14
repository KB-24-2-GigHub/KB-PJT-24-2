package com.gighub.document.service;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.document.dto.DocumentListItem;
import com.gighub.document.storage.DocumentStorageAdapter;
import com.gighub.document.validation.UploadedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 보건증 등록 요청을 검증하고 {@link HealthCertificateRegisterTransaction}에 위임한 뒤,
 * Commit된 문서를 공개 목록 Item으로 돌려준다.
 */
@Service
public class HealthCertificateRegisterServiceImpl implements HealthCertificateRegisterService {

    private static final Logger log = LoggerFactory.getLogger(HealthCertificateRegisterServiceImpl.class);

    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Seoul");
    private static final String DOC_TYPE = "HEALTH_CERTIFICATE";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_EXPIRED = "EXPIRED";
    private static final String SOURCE_OWN = "OWN";
    private static final int LATEST_VERSION_ORIGINAL = 1;

    private final HealthCertificateRegistrationValidator validator;
    private final HealthCertificateRegisterTransaction transaction;
    private final DocumentStorageAdapter storageAdapter;
    private final Clock clock;

    @Autowired
    public HealthCertificateRegisterServiceImpl(
            HealthCertificateRegistrationValidator validator,
            HealthCertificateRegisterTransaction transaction,
            DocumentStorageAdapter storageAdapter) {
        this(validator, transaction, storageAdapter, Clock.system(DATABASE_ZONE));
    }

    HealthCertificateRegisterServiceImpl(
            HealthCertificateRegistrationValidator validator,
            HealthCertificateRegisterTransaction transaction,
            DocumentStorageAdapter storageAdapter,
            Clock clock) {
        this.validator = validator;
        this.transaction = transaction;
        this.storageAdapter = storageAdapter;
        this.clock = clock;
    }

    @Override
    public DocumentListItem register(
            AuthPrincipal principal, String docType, LocalDate issuedDate, UploadedFile file) {
        ValidatedHealthCertificateRegistration validated =
                validator.validate(principal, docType, issuedDate, file);
        HealthCertificateRegistrationHandle handle = transaction.register(validated);
        promote(handle);
        return toResponse(principal, validated, handle);
    }

    /**
     * 승격은 Commit 뒤의 Best-Effort 경계다. 이미 확정된 등록을 실패로 되돌릴 수 없으므로
     * 실패는 기록만 남긴다. 조회 시점에 임시 Object를 다시 검증해 Fallback한다.
     */
    private void promote(HealthCertificateRegistrationHandle handle) {
        try {
            storageAdapter.promote(
                    handle.pendingStorageKey(), handle.finalStorageKey(), handle.checksum());
        } catch (RuntimeException failure) {
            log.warn("보건증 파일 승격에 실패했습니다. documentId={}", handle.documentId(), failure);
        }
    }

    private DocumentListItem toResponse(
            AuthPrincipal principal,
            ValidatedHealthCertificateRegistration validated,
            HealthCertificateRegistrationHandle handle) {
        LocalDate today = LocalDate.now(clock);
        String status = handle.expiresDate().isBefore(today) ? STATUS_EXPIRED : STATUS_ACTIVE;
        return DocumentListItem.of(
                handle.documentId(),
                DOC_TYPE,
                status,
                validated.file().mimeType(),
                validated.issuedDate(),
                handle.expiresDate(),
                LATEST_VERSION_ORIGINAL,
                SOURCE_OWN,
                principal.getName(),
                null,
                null,
                null,
                null,
                null,
                // 공유 후보(ACTIVE Work Case) 계산은 공유 조회 경로(#181)의 책임이라, 등록
                // 응답은 안전한 기본값 false로 돌려주고 실제 값은 이후 목록 조회로 받는다.
                false,
                LocalDateTime.now(clock));
    }
}
