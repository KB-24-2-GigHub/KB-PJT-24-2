package com.gighub.work.domain;

/**
 * 저장된 근무 상태와 성공 근태 기록이 서로 모순되는 경우입니다.
 *
 * <p>어느 쪽이 맞는지 조회 API가 임의로 정정하지 않습니다. 값은 저장된 그대로 응답하고 이
 * 목록을 내부 무결성 신호로만 남겨, 잘못된 데이터가 정상처럼 보이지 않게 합니다.</p>
 */
public enum AttendanceStateViolation {

    /** 출근 전 상태({@code ACCEPTED}, {@code READY})인데 성공 CHECK_IN이 있습니다. */
    CHECK_IN_ON_NOT_STARTED_STATUS,

    /** {@code IN_PROGRESS}인데 성공 CHECK_IN이 없습니다. */
    MISSING_CHECK_IN_ON_IN_PROGRESS,

    /** {@code CHECK_OUT_MISSING}인데 성공 CHECK_IN이 없습니다. */
    MISSING_CHECK_IN_ON_CHECK_OUT_MISSING,

    /** {@code CHECK_OUT_MISSING}인데 성공 CHECK_OUT이 있습니다. */
    CHECK_OUT_ON_CHECK_OUT_MISSING,

    /** 미출근({@code NO_SHOW})인데 성공 근태 기록이 있습니다. */
    ATTENDANCE_ON_NO_SHOW,

    /** 성공 CHECK_IN 없이 성공 CHECK_OUT만 있습니다. */
    CHECK_OUT_WITHOUT_CHECK_IN,

    /** 성공 CHECK_OUT이 성공 CHECK_IN보다 앞섭니다. */
    CHECK_OUT_BEFORE_CHECK_IN
}
