package com.gighub.document.service;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.document.dto.DocumentListItem;

import java.time.LocalDate;

/** 소유 WORKER의 ACTIVE 보건증 발급일을 수정한다(DOC-006). */
public interface HealthCertificateUpdateService {

    DocumentListItem updateIssuedDate(AuthPrincipal principal, long documentId, LocalDate issuedDate);
}
