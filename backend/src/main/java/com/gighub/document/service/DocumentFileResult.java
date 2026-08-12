package com.gighub.document.service;

import lombok.Builder;
import lombok.Getter;

/** 권한 검증을 통과한 사용자에게 보낼 문서 파일 내용과 안전한 응답 Header 값입니다. */
@Getter
@Builder
public class DocumentFileResult {
    private final byte[] content;
    private final String mimeType;
    private final String fileName;
    private final String asciiFileName;
    private final boolean forceAttachment;
}
