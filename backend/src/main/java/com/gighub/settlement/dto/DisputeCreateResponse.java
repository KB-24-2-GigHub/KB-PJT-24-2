package com.gighub.settlement.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 분쟁 생성 성공 payload입니다. */
@Getter
@AllArgsConstructor
public class DisputeCreateResponse {
    private final Long reportId;
}
