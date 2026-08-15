package com.gighub.document.dto;

import java.time.LocalDate;

import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 보건증 발급일 수정 입력입니다(DOC-006). 파일 교체·Version 추가는 받지 않습니다. */
public final class HealthCertificateUpdateRequest {

    @NotNull(message = "발급일은 필수입니다.")
    private final LocalDate issuedDate;

    @JsonCreator
    public HealthCertificateUpdateRequest(@JsonProperty("issuedDate") LocalDate issuedDate) {
        this.issuedDate = issuedDate;
    }

    public LocalDate getIssuedDate() {
        return issuedDate;
    }

    /** 명세에 없는 수정 필드는 조용히 무시하지 않고 요청 오류로 처리합니다. */
    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("허용되지 않은 보건증 수정 필드입니다: " + fieldName);
    }
}
