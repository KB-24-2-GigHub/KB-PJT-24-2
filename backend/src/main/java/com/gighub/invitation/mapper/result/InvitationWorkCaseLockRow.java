package com.gighub.invitation.mapper.result;

import com.gighub.work.domain.WorkCaseStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 초대를 발급하기 전에 잠근 근무 행의 판단 근거입니다.
 *
 * <p>소유권, 매칭 여부, 상태, 시작 시각, 조건 Version은 모두 발급 가능 여부를 정하는 값이라
 * 잠금 조회에서 함께 읽습니다. 잠금 없이 읽으면 조건 수정과 발급이 같은 근무에서 동시에
 * 성립해 이전 조건의 Link가 새 조건과 함께 살아남을 수 있습니다.</p>
 *
 * <p>제목·사업장명·금액은 담지 않습니다. 발급 응답에 필요하지 않고, 조회 흐름의
 * {@link InvitationWorkCaseRow}가 이미 읽고 있어 두 경로가 같은 필드를 각자 들면 어느 쪽이
 * 최신인지 알 수 없게 됩니다.</p>
 */
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class InvitationWorkCaseLockRow {

    private Long workCaseId;
    private Long employerId;
    private Long workerId;
    private WorkCaseStatus status;
    private Integer termsVersion;
    private LocalDateTime startsAt;
}
