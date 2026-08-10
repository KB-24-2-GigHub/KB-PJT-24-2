package com.gighub.invitation.mapper.result;

import com.gighub.work.domain.WorkCaseStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 초대가 가리키는 근무의 현재 조건입니다.
 *
 * <p>초대 도메인이 자기 SQL로 {@code work_cases}를 읽습니다. 읽기 전용 조회라 근무 도메인
 * 구현을 기다리지 않아도 되고, 같은 파일을 함께 고치지 않아 작업이 서로 막히지 않습니다.</p>
 *
 * <p>좌표와 인증 반경은 초대 조회 응답에 없으므로 SELECT 자체에서 뺍니다. 읽지 않아야 이후
 * 계층이 실수로 근무지 좌표를 노출할 수 없습니다.</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvitationWorkCaseRow {

    private Long workCaseId;
    private Long employerId;
    private Long workerId;
    private String title;
    private String workplaceName;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private Integer breakMinutes;
    private Boolean breakPaid;
    private Long dailyWage;
    private Integer termsVersion;
    private WorkCaseStatus status;
}
