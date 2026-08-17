package com.gighub.document.service;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.ConflictException;
import com.gighub.document.mapper.ContractDocumentWriteMapper;
import com.gighub.document.mapper.DocumentQueryMapper;
import com.gighub.notification.service.NotificationRecorder;
import com.gighub.document.mapper.param.DocumentShareInsertParam;
import com.gighub.member.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 검증된 공유 한 건이 승인된 행 모양으로 저장되고 중복이 409로 바뀌는지 확인합니다. */
class HealthCertificateShareServiceImplTest {

    private static final long WORKER_ID = 7L;
    private static final long DOCUMENT_ID = 11L;
    private static final long WORKPLACE_ID = 3L;
    private static final long WORK_CASE_ID = 21L;
    private static final long OWNER_ID = 5L;
    private static final long SHARE_ID = 99L;

    private final HealthCertificateShareValidator validator =
            mock(HealthCertificateShareValidator.class);
    private final ContractDocumentWriteMapper documentMapper =
            mock(ContractDocumentWriteMapper.class);

    private final DocumentQueryMapper documentQueryMapper = mock(DocumentQueryMapper.class);
    private final NotificationRecorder notificationRecorder = mock(NotificationRecorder.class);

    private final HealthCertificateShareServiceImpl service =
            new HealthCertificateShareServiceImpl(
                    validator, documentMapper, documentQueryMapper, notificationRecorder);

    @Test
    void storesTheServerDerivedRelationshipAsAnActiveHealthCertificateShare() {
        stubValidatedShare();
        stubGeneratedShareId();

        long shareId = service.share(worker(), DOCUMENT_ID, WORKPLACE_ID);

        assertEquals(SHARE_ID, shareId);

        ArgumentCaptor<DocumentShareInsertParam> captor =
                ArgumentCaptor.forClass(DocumentShareInsertParam.class);
        verify(documentMapper).insertShare(captor.capture());
        DocumentShareInsertParam param = captor.getValue();
        assertEquals(DOCUMENT_ID, param.getDocumentId());
        // Client가 보낸 workplaceId가 아니라 Validator가 파생한 관계로 저장한다.
        assertEquals(WORK_CASE_ID, param.getWorkCaseId());
        assertEquals(OWNER_ID, param.getSharedWithUserId());
        assertEquals("HEALTH_CERTIFICATE", param.getPurpose());
    }

    /**
     * 활성 공유 유일성의 최종 판정은 DB 제약이다. 애플리케이션이 먼저 조회해 거르는 방식은
     * 조회와 삽입 사이를 막지 못해 동시 요청에서 두 행이 생긴다.
     */
    @Test
    void translatesTheActiveShareUniqueViolationIntoConflict() {
        stubValidatedShare();
        doThrow(new DuplicateKeyException("uk_document_shares_active"))
                .when(documentMapper).insertShare(any());

        assertThrows(ConflictException.class,
                () -> service.share(worker(), DOCUMENT_ID, WORKPLACE_ID));
    }

    /** 제약 이름과 Key 값이 섞인 원본 메시지를 사용자 응답으로 내보내지 않는다. */
    @Test
    void keepsTheConflictMessageFreeOfConstraintDetail() {
        stubValidatedShare();
        doThrow(new DuplicateKeyException(
                "Duplicate entry '11-21-5-HEALTH_CERTIFICATE-1' for key 'uk_document_shares_active'"))
                .when(documentMapper).insertShare(any());

        ConflictException thrown = assertThrows(ConflictException.class,
                () -> service.share(worker(), DOCUMENT_ID, WORKPLACE_ID));

        assertEquals("이미 이 사업장에 공유 중인 보건증입니다.", thrown.getMessage());
    }

    @Test
    void doesNotInsertAnythingWhenValidationRejectsTheRequest() {
        doThrow(new ConflictException("후보가 여러 건입니다."))
                .when(validator).validate(any(), org.mockito.ArgumentMatchers.anyLong(), any());

        assertThrows(ConflictException.class,
                () -> service.share(worker(), DOCUMENT_ID, WORKPLACE_ID));

        verify(documentMapper, never()).insertShare(any());
    }

    private void stubValidatedShare() {
        when(validator.validate(any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(new ValidatedHealthCertificateShare(
                        DOCUMENT_ID, WORK_CASE_ID, OWNER_ID));
    }

    private void stubGeneratedShareId() {
        doAnswer(invocation -> {
            DocumentShareInsertParam param = invocation.getArgument(0);
            param.setId(SHARE_ID);
            return 1;
        }).when(documentMapper).insertShare(any());
    }

    private AuthPrincipal worker() {
        return new AuthPrincipal(WORKER_ID, UserRole.WORKER, "이알바");
    }
}
