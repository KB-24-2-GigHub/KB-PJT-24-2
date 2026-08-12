package com.gighub.invitation.service;

import com.gighub.invitation.application.InvitationAcceptanceResult;

import java.util.Objects;

/**
 * 수락 결과와 그것이 최초 성공인지 저장된 결과의 Replay인지를 함께 전달합니다.
 *
 * <p>두 경우의 Body와 상태 코드는 같고 {@code Idempotency-Replayed} Header만 다릅니다.
 * Controller가 응답 값만 보고 둘을 구분할 수 없으므로 결과 타입으로 나눕니다.</p>
 */
public final class InvitationAcceptResult {

    private final InvitationAcceptanceResult result;
    private final boolean replayed;

    private InvitationAcceptResult(InvitationAcceptanceResult result, boolean replayed) {
        this.result = Objects.requireNonNull(result, "result");
        this.replayed = replayed;
    }

    /** 이 요청이 실제로 수락을 처리했습니다. */
    public static InvitationAcceptResult first(InvitationAcceptanceResult result) {
        return new InvitationAcceptResult(result, false);
    }

    /** 같은 요청이 이미 성공했고 저장된 결과를 그대로 돌려줍니다. */
    public static InvitationAcceptResult replayed(InvitationAcceptanceResult result) {
        return new InvitationAcceptResult(result, true);
    }

    public InvitationAcceptanceResult getResult() {
        return result;
    }

    public boolean isReplayed() {
        return replayed;
    }
}
