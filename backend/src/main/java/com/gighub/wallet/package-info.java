/**
 * Wallet 논리 모듈에서 KRW 지갑, 충전·출금, 임금 선예치와 불변 원장을 담당합니다.
 *
 * <p>다른 모듈은 공개 Wallet/Escrow Command로만 자금 변경을 요청합니다. Mock 계좌 접근은
 * Bank Adapter Gateway를 사용하며 Bank Mapper를 직접 호출하지 않습니다.</p>
 */
package com.gighub.wallet;

