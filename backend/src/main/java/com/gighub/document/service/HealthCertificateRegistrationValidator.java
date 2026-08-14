package com.gighub.document.service;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.common.exception.ValidationException;
import com.gighub.document.validation.HealthCertificateFileValidator;
import com.gighub.member.domain.UserRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * {@code POST /api/documents}의 보건증 등록 요청을 DEC-HEALTH-CERTIFICATE-LIFECYCLE·DOC-005
 * 결정값으로 검증합니다: 인증 WORKER 본인만 등록하고, {@code docType}은 HEALTH_CERTIFICATE만
 * 받으며, 발급일은 서울 서버 수신 날짜보다 미래일 수 없습니다.
 */
@Component
public class HealthCertificateRegistrationValidator {

    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Seoul");
    private static final String HEALTH_CERTIFICATE_TYPE = "HEALTH_CERTIFICATE";

    private final HealthCertificateFileValidator fileValidator;
    private final Clock clock;

    @Autowired
    public HealthCertificateRegistrationValidator(HealthCertificateFileValidator fileValidator) {
        this(fileValidator, Clock.system(DATABASE_ZONE));
    }

    HealthCertificateRegistrationValidator(
            HealthCertificateFileValidator fileValidator, Clock clock) {
        this.fileValidator = fileValidator;
        this.clock = clock;
    }

    public ValidatedHealthCertificateRegistration validate(
            AuthPrincipal principal, String docType, LocalDate issuedDate, MultipartFile file) {
        requireWorkerRole(principal);
        requireHealthCertificateType(docType);
        requireNotFutureIssuedDate(issuedDate);
        return new ValidatedHealthCertificateRegistration(
                principal.getUserId(), issuedDate, fileValidator.validate(file));
    }

    private void requireWorkerRole(AuthPrincipal principal) {
        if (principal.getRole() != UserRole.WORKER) {
            throw new RoleMismatchException("보건증 등록은 WORKER만 사용할 수 있습니다.");
        }
    }

    private void requireHealthCertificateType(String docType) {
        if (!HEALTH_CERTIFICATE_TYPE.equals(docType)) {
            throw new ValidationException(
                    "보건증 등록은 docType=HEALTH_CERTIFICATE만 받습니다.", "docType", "UNSUPPORTED_TYPE");
        }
    }

    private void requireNotFutureIssuedDate(LocalDate issuedDate) {
        if (issuedDate == null) {
            throw new ValidationException("발급일이 필요합니다.", "issuedDate", "REQUIRED");
        }
        if (issuedDate.isAfter(LocalDate.now(clock))) {
            throw new ValidationException("발급일은 미래일 수 없습니다.", "issuedDate", "FUTURE_DATE");
        }
    }
}
