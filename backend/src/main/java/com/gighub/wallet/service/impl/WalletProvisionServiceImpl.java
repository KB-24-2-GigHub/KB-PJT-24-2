package com.gighub.wallet.service.impl;

import com.gighub.wallet.mapper.WalletMapper;
import com.gighub.wallet.service.WalletProvisionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Wallet 생성 SQL과 결과 행 검증을 Wallet 모듈 안에 둡니다. */
@Service
public class WalletProvisionServiceImpl implements WalletProvisionService {

    private final WalletMapper walletMapper;

    public WalletProvisionServiceImpl(WalletMapper walletMapper) {
        this.walletMapper = walletMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void provisionKrwWallet(long userId) {
        if (userId <= 0 || walletMapper.insertKrwWallet(userId) != 1) {
            throw new IllegalStateException("가입 지갑 저장 결과가 올바르지 않습니다.");
        }
    }
}
