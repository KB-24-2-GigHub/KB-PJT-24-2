/**
 * Gig Hub 백엔드의 최상위 패키지입니다.
 *
 * <p>하위 패키지는 물리 배치이고 논리 모듈 경계는 {@code docs/agent/MODULE_BOUNDARIES.md}를
 * 따릅니다. {@code work}/{@code invitation}/{@code contract}는 하나의 Work 모듈이고,
 * {@code auth}/{@code member}/{@code badge}는 하나의 Member/Auth 모듈입니다.</p>
 *
 * <p>Controller는 Application Service 또는 Orchestrator만 호출하고, 쓰기 Service는 자기 논리
 * 모듈의 Mapper만 호출합니다. 타 모듈에는 공개 Command/Query 경계와 최소 Result만 노출하며
 * Mapper Row/Param과 내부 Domain 객체를 전달하지 않습니다.</p>
 */
package com.gighub;

