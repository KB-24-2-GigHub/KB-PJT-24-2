package com.gighub.settlement.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;

/** 분쟁 신고 제목과 경위입니다. trim 이후 길이 검증은 Service가 수행합니다. */
@Getter
@NoArgsConstructor
public class DisputeCreateRequest {

    @NotNull(message = "제목을 입력해 주세요.")
    private String title;

    @NotNull(message = "경위를 입력해 주세요.")
    private String content;

    public DisputeCreateRequest(String title, String content) {
        this.title = title;
        this.content = content;
    }
}
