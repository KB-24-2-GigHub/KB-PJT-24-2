package com.gighub.member.dto;

import javax.validation.constraints.NotBlank;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 승인 명세의 회원 탈퇴 입력입니다. 현재 비밀번호만 허용합니다.
 *
 * <p>비밀번호는 trim·대소문자 변환·절단 없이 원문 그대로 다룹니다(DEC-AUTH-INPUT).
 * 길이·byte 규칙을 걸지 않습니다. 대조 대상일 뿐 새로 만드는 값이 아니고, 형식 오류로
 * 갈라놓으면 저장된 비밀번호의 형태를 응답으로 알려주게 됩니다.</p>
 */
public final class WithdrawalRequest {

    @NotBlank(message = "비밀번호는 필수입니다.")
    private final String password;

    @JsonCreator
    public WithdrawalRequest(@JsonProperty("password") String password) {
        this.password = password;
    }

    public String getPassword() {
        return password;
    }

    /** 명세에 없는 탈퇴 필드는 조용히 무시하지 않고 요청 오류로 처리합니다. */
    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("허용되지 않은 탈퇴 요청 필드입니다: " + fieldName);
    }
}
