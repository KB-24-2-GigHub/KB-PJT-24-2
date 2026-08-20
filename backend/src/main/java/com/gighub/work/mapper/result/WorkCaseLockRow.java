package com.gighub.work.mapper.result;

import com.gighub.work.domain.WorkCaseStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 수정·삭제 전에 잠근 근무 행의 판단 근거입니다.
 *
 * <p>상태 확인과 변경 사이에 다른 요청이 끼어들지 못하도록 잠금 조회에서 함께 읽습니다.
 * 잠금 없이 읽으면 조건 수정과 초대 수락이 같은 근무에서 동시에 성립할 수 있습니다.</p>
 *
 * <p>잠긴 같은 행에서 최초 정산 Snapshot까지 확정해야 하므로 약정액과 휴게 조건도 함께
 * 읽습니다. Attendance는 이 값을 계산 명령에 전달할 뿐 Work 행을 다시 조회하지 않습니다.</p>
 *
 * <p>MyBatis가 &lt;constructor&gt; 매핑으로 생성하므로 필드 선언 순서가 곧 생성자 인자
 * 순서입니다. XML과 함께 바꿔야 합니다.</p>
 */
@Getter
@Builder(toBuilder = true)
@AllArgsConstructor
public class WorkCaseLockRow {

    private final Long workCaseId;
    private final Long employerId;
    private final Long workplaceId;
    private final Long workerId;
    private final WorkCaseStatus status;
    private final Integer termsVersion;
    private final LocalDateTime startsAt;
    private final LocalDateTime endsAt;
    private final Long agreedWage;
    private final Integer breakMinutes;
    private final Boolean breakPaid;
}
