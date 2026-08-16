package com.gighub.document.service;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.ConflictException;
import com.gighub.document.mapper.ContractDocumentWriteMapper;
import com.gighub.document.mapper.DocumentQueryMapper;
import com.gighub.document.mapper.param.DocumentShareInsertParam;
import com.gighub.notification.domain.NotificationType;
import com.gighub.notification.service.NotificationRecorder;
import com.gighub.notification.service.command.NotificationRecordCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 검증된 보건증 공유 한 건을 {@code document_shares}에 만듭니다(DOC-007).
 *
 * <p>활성 공유 유일성의 최종 판정은 {@code uk_document_shares_active} 제약이 합니다. 이
 * 제약은 {@code active_slot} 생성 Column을 써서 ACTIVE 행만 유일하게 묶고 REVOKED 이력은
 * 몇 건이든 남깁니다. 애플리케이션이 먼저 조회해 중복을 거르는 방식은 조회와 삽입 사이에
 * 다른 요청이 끼어들 수 있어 동시 요청을 막지 못하므로, 제약 위반을 잡아 409로 옮기는 쪽만
 * 쓴다.</p>
 *
 * <p>철회된 행은 되살리지 않고 재공유는 새 행을 만듭니다(DEC-DOCUMENT-SHARE-UNIT). 되살리면
 * 언제 공유했다 끊었는지가 이력에서 사라집니다.</p>
 */
@Service
@RequiredArgsConstructor
public class HealthCertificateShareServiceImpl implements HealthCertificateShareService {

    private static final String PURPOSE_HEALTH_CERTIFICATE = "HEALTH_CERTIFICATE";

    private final HealthCertificateShareValidator validator;
    private final ContractDocumentWriteMapper documentMapper;
    private final DocumentQueryMapper documentQueryMapper;
    private final NotificationRecorder notificationRecorder;

    @Override
    @Transactional
    public long share(AuthPrincipal principal, long documentId, Long workplaceId) {
        ValidatedHealthCertificateShare validated =
                validator.validate(principal, documentId, workplaceId);

        DocumentShareInsertParam param = DocumentShareInsertParam.builder()
                .documentId(validated.documentId())
                .workCaseId(validated.workCaseId())
                .sharedWithUserId(validated.sharedWithUserId())
                .purpose(PURPOSE_HEALTH_CERTIFICATE)
                .build();

        try {
            if (documentMapper.insertShare(param) != 1) {
                throw new IllegalStateException("보건증 공유 행을 저장하지 못했습니다.");
            }
        } catch (DuplicateKeyException duplicate) {
            // 같은 문서·Work Case·OWNER·목적의 ACTIVE 공유가 이미 있다. 예외 메시지에는 제약
            // 이름과 Key 값이 섞이므로 로그 경계로 넘기지 않고 안전한 문구만 남긴다.
            throw new ConflictException("이미 이 사업장에 공유 중인 보건증입니다.");
        }

        long shareId = Objects.requireNonNull(param.getId(), "생성된 공유 식별자");
        recordSharedNotification(validated, shareId);
        return shareId;
    }

    /**
     * 공유 사실을 사업장 OWNER에게 알립니다.
     *
     * <p>이 Transaction 안에서 부르지만 적재는 Commit 이후이며, 알림이 실패해도 공유는 그대로
     * 유지됩니다(SPEC-384-01). 중복 판정 기준은 근무가 아니라 방금 만든 공유 행이라, 철회 뒤
     * 재공유하면 두 번째 알림도 정상으로 나갑니다.</p>
     */
    private void recordSharedNotification(
            ValidatedHealthCertificateShare validated,
            long shareId) {
        String workCaseTitle = documentQueryMapper.findWorkCaseTitle(validated.workCaseId());
        if (workCaseTitle == null) {
            return;
        }
        notificationRecorder.record(NotificationRecordCommand.builder()
                .type(NotificationType.DOC_SHARED)
                .sourceId(shareId)
                .workCaseId(validated.workCaseId())
                .workCaseTitle(workCaseTitle)
                .recipientUserIds(List.of(validated.sharedWithUserId()))
                .build());
    }
}
