package com.gighub.invitation.application;

/** 멱등 Claim에 저장할 수락 결과 Snapshot을 opaque JSON 문자열로 변환하는 경계입니다. */
public interface InvitationAcceptanceReplaySnapshotCodec {

    String writeResponseBody(InvitationAcceptanceResult result);

    InvitationAcceptanceResult readResponseBody(String storedBody);
}
