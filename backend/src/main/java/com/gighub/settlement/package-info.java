/**
 * Settlement 논리 모듈에서 근무 완료 후 정산, 환불과 Deferred 분쟁 상태를 담당합니다.
 *
 * <p>정산 상태 쓰기는 이 모듈이 소유합니다. 다중 모듈 지급은 Application Orchestrator가 outer
 * Transaction을 소유하고 Work·Wallet 공개 경계를 호출하며, 이 모듈은 타 모듈 Mapper를 직접
 * 사용하지 않습니다.</p>
 */
package com.gighub.settlement;

