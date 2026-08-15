package com.gighub.document.service;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.document.dto.DocumentListItem;
import com.gighub.document.validation.UploadedFile;

import java.time.LocalDate;

/** WORKER 본인의 보건증을 등록한다(DOC-005). */
public interface HealthCertificateRegisterService {

    DocumentListItem register(
            AuthPrincipal principal, String docType, LocalDate issuedDate, UploadedFile file);
}
