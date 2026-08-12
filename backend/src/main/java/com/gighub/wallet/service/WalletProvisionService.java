package com.gighub.wallet.service;

/** 회원가입 outer Transaction에 참여해 기본 Wallet을 만드는 공개 명령입니다. */
public interface WalletProvisionService {

    /** 사용자당 하나의 KRW Wallet을 만들고 영향 행을 검증합니다. */
    void provisionKrwWallet(long userId);
}
