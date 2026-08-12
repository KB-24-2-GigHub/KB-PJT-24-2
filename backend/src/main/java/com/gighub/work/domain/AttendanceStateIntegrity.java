package com.gighub.work.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;


/**
 * 저장된 근무 상태와 성공 근태 기록이 서로 맞는지 확인합니다.
 *
 * <p>조회 API는 두 사실을 합쳐 하나의 화면 값으로 보여 주므로, 둘이 어긋나면 Frontend가
 * 무엇이 잘못됐는지 알 수 없는 채로 정상 화면을 그립니다. 이 클래스는 어긋남을 목록으로
 * 돌려주고 값 자체는 바꾸지 않습니다. 조회 응답을 실패로 만들지 않는 이유는, 이미 저장된
 * 모순 때문에 WORKER가 본인 근무를 전혀 볼 수 없게 되는 편이 더 나쁘기 때문입니다.
 * 호출부가 이 결과를 서버 로그에 남깁니다.</p>
 *
 * <p>{@code COMPLETED}는 검사하지 않습니다. {@link com.gighub.work.domain.WorkCasePolicy}의
 * 전이표가 {@code ACCEPTED}와 {@code READY}에서 근태 없이 바로 {@code COMPLETED}로 가는 길을
 * 허용하므로, 근태가 없는 {@code COMPLETED}는 모순이 아닙니다.</p>
 */
public final class AttendanceStateIntegrity {

    /** 성공 CHECK_IN이 아직 있을 수 없는 상태입니다. */
    private static final Set<WorkCaseStatus> NOT_STARTED =
            EnumSet.of(WorkCaseStatus.ACCEPTED, WorkCaseStatus.READY);

    private AttendanceStateIntegrity() {
    }

    /**
     * @param status       저장된 근무 상태
     * @param checkedInAt  성공 CHECK_IN 시각. 없으면 {@code null}
     * @param checkedOutAt 성공 CHECK_OUT 시각. 없으면 {@code null}
     * @return 발견한 모순. 모순이 없으면 빈 목록
     */
    public static List<AttendanceStateViolation> verify(
            WorkCaseStatus status,
            LocalDateTime checkedInAt,
            LocalDateTime checkedOutAt) {
        List<AttendanceStateViolation> violations = new ArrayList<>();

        if (checkedOutAt != null && checkedInAt == null) {
            violations.add(AttendanceStateViolation.CHECK_OUT_WITHOUT_CHECK_IN);
        }
        if (checkedInAt != null && checkedOutAt != null && checkedOutAt.isBefore(checkedInAt)) {
            violations.add(AttendanceStateViolation.CHECK_OUT_BEFORE_CHECK_IN);
        }
        if (NOT_STARTED.contains(status) && checkedInAt != null) {
            violations.add(AttendanceStateViolation.CHECK_IN_ON_NOT_STARTED_STATUS);
        }
        if (status == WorkCaseStatus.IN_PROGRESS && checkedInAt == null) {
            violations.add(AttendanceStateViolation.MISSING_CHECK_IN_ON_IN_PROGRESS);
        }
        if (status == WorkCaseStatus.CHECK_OUT_MISSING) {
            if (checkedInAt == null) {
                violations.add(AttendanceStateViolation.MISSING_CHECK_IN_ON_CHECK_OUT_MISSING);
            }
            if (checkedOutAt != null) {
                violations.add(AttendanceStateViolation.CHECK_OUT_ON_CHECK_OUT_MISSING);
            }
        }
        if (status == WorkCaseStatus.NO_SHOW && (checkedInAt != null || checkedOutAt != null)) {
            violations.add(AttendanceStateViolation.ATTENDANCE_ON_NO_SHOW);
        }

        return List.copyOf(violations);
    }
}
