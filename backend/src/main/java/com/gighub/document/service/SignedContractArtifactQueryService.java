package com.gighub.document.service;

/** 다른 모듈이 계약 Artifact의 준비 여부만 확인하는 최소 Document Query 경계입니다. */
public interface SignedContractArtifactQueryService {

    boolean isReadable(long workCaseId);
}
