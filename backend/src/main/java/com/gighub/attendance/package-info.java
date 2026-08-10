/**
 * Attendance 논리 모듈에서 사업장 고정 QR과 위치 확인을 이용한 출퇴근 인증을 담당합니다.
 *
 * <p>근태 기록과 QR 쓰기는 이 모듈이 소유합니다. Work 상태를 바꿀 때는 Work 공개 Command를
 * 요청하며 Work Mapper를 직접 호출하지 않습니다.</p>
 */
package com.gighub.attendance;

