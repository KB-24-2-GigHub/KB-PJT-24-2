package com.gighub.invitation.mapper.result;

import com.gighub.invitation.domain.InvitationStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 초대 한 건의 조회 결과입니다.
 *
 * <p>{@code status}는 DB 문자열을 {@link InvitationStatus}로 변환한 Write 판단 값입니다.
 * 목록·상세 API Projection의 문자열과 구분해 상태 정책이 저장 문자열에 흩어지지 않게 합니다.</p>
 *
 * <p>{@code tokenHash}만 담고 Token 원문 필드는 두지 않습니다. 저장소에 원문이 없으므로 이
 * 행에도 담을 값이 없고, 필드를 만들어 두면 이후 계층이 응답이나 로그에 실을 수 있습니다.</p>
 */
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class InvitationRow {

    private Long id;
    private Long workCaseId;
    private byte[] tokenHash;
    private InvitationStatus status;
    private Integer expectedTermsVersion;
    private LocalDateTime expiresAt;
    private Long acceptedByUserId;
}
