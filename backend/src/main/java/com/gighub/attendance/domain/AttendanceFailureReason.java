package com.gighub.attendance.domain;

/**
 * 거절된 스캔 시도의 감사 사유입니다.
 *
 * <p>{@code attendance_records.failure_reason}에 상수 이름 그대로 저장하므로 이름을 바꾸면
 * 이미 쌓인 감사 기록과 어긋납니다. 사용자에게 보내는 메시지와 분리해 두어, 문구를 다듬어도
 * 감사 분석 기준이 흔들리지 않게 합니다.</p>
 */
public enum AttendanceFailureReason {

    /** 사업장 좌표 Snapshot이 없어 거리 판정을 할 수 없습니다. */
    WORKPLACE_LOCATION_MISSING,

    /** 서버가 계산한 거리가 승인된 반경을 넘었습니다. */
    DISTANCE_EXCEEDED,

    /** 잠근 뒤 확인한 근무 상태가 요청한 출퇴근을 허용하지 않습니다. */
    WORK_STATE_NOT_SCANNABLE,

    /** 이미 성공한 출근과 퇴근이 모두 있어 더 처리할 스캔이 없습니다. */
    ALREADY_COMPLETED,

    /** 조건부 상태 전이가 경쟁에서 밀려 0행을 바꿨습니다. */
    TRANSITION_LOST_RACE
}
