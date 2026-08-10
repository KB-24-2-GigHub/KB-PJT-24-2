package com.gighub.attendance.service;

import com.gighub.document.service.SignedContractArtifactQueryService;
import org.springframework.stereotype.Component;

/** READY 전이 전에 최종 서명 계약 파일의 존재와 Checksum을 검증합니다. */
@Component
public class SignedContractArtifactVerifier {

    private final SignedContractArtifactQueryService artifactQueryService;

    public SignedContractArtifactVerifier(
            SignedContractArtifactQueryService artifactQueryService) {
        this.artifactQueryService = artifactQueryService;
    }

    public boolean isReadable(long workCaseId) {
        return artifactQueryService.isReadable(workCaseId);
    }
}
