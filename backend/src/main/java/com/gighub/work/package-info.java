/**
 * Work 논리 모듈에서 근무 건과 상태 정책을 담당합니다.
 *
 * <p>{@code invitation}과 {@code contract}는 별도 최상위 모듈이 아니라 같은 Work 경계의 하위
 * 역할입니다. 요청 사이에는 업무 객체나 Lock을 보존하지 않고, 저장된 상태와
 * {@code terms_version}을 각 명령에서 다시 검증합니다.</p>
 */
package com.gighub.work;

