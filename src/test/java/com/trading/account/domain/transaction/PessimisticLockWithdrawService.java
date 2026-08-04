package com.trading.account.domain.transaction;

import com.trading.account.common.exception.CustomException;
import com.trading.account.common.exception.ErrorCode;
import com.trading.account.domain.account.Account;
import com.trading.account.domain.account.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

// IS-30 실험 전용: TransactionService.withdraw()와 동일한 흐름이지만 비관적 락을 쓰고
// @Retryable이 없다 — 낙관적 락(재시도) vs 비관적 락(대기)을 같은 시나리오로 비교하기 위한
// 테스트 전용 서비스. 프로덕션 코드(TransactionService)는 그대로 낙관적 락 유지.
@Service
@RequiredArgsConstructor
class PessimisticLockWithdrawService {

    private final AccountRepository accountRepository;
    private final TransactionHistoryRepository transactionHistoryRepository;

    @Transactional
    public void withdraw(String accountNumber, BigDecimal amount) {
        Account account = accountRepository.findByAccountNumberForUpdate(accountNumber)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
        account.withdraw(amount);
        accountRepository.flush();
        transactionHistoryRepository.save(new TransactionHistory(account, null, amount, TransactionType.WITHDRAW));
    }
}
