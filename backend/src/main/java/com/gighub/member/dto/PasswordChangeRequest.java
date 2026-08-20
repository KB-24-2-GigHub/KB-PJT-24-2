package com.gighub.member.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gighub.auth.validation.Utf8ByteLength;

/**
 * 승인 명세의 비밀번호 변경 입력입니다. 현재 비밀번호와 새 비밀번호만 허용합니다.
 *
 * <p>두 값 모두 trim·대소문자 변환·절단 없이 원문 그대로 다룹니다(DEC-AUTH-INPUT).
 * {@code currentPassword}에는 길이·byte 규칙을 걸지 않습니다. 대조 대상일 뿐 새로 만드는
 * 값이 아니고, 형식 오류로 갈라놓으면 저장된 비밀번호의 형태를 응답으로 알려주게 됩니다.</p>
 */
public final class PasswordChangeRequest {

    @NotBlank(message = "현재 비밀번호는 필수입니다.")
    private final String currentPassword;

    @NotBlank(message = "새 비밀번호는 필수입니다.")
    @Size(min = 8, max = 64, message = "비밀번호는 8자 이상 64자 이하여야 합니다.")
    @Utf8ByteLength(max = 72, message = "비밀번호는 UTF-8 기준 72byte 이하여야 합니다.")
    private final String newPassword;

    @JsonCreator
    public PasswordChangeRequest(
            @JsonProperty("currentPassword") String currentPassword,
            @JsonProperty("newPassword") String newPassword) {
        this.currentPassword = currentPassword;
        this.newPassword = newPassword;
    }

    public String getCurrentPassword() {
        return currentPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    /** 명세에 없는 비밀번호 변경 필드는 조용히 무시하지 않고 요청 오류로 처리합니다. */
    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("허용되지 않은 비밀번호 변경 필드입니다: " + fieldName);
    }
}
