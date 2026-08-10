/**
 * Work 논리 모듈 안에서 근무 초대 Link의 발급, 조회와 수락 역할을 담당합니다.
 *
 * <p>초대는 사전 지정 WORKER가 없는 Bearer Link이므로, Link 원문을 아는 인증 WORKER가
 * 당사자 후보가 됩니다. 원문 Token은 발급 응답에서만 노출하고 저장소에는 Hash만
 * 남깁니다. 장기 {@code PENDING} 상태는 DB에 저장하고 요청 사이에 Thread나 Transaction을
 * 유지하지 않습니다.</p>
 */
package com.gighub.invitation;
