package com.trading.account.domain.reconciliation;

import com.trading.account.domain.account.Account;
import com.trading.account.domain.transaction.TransactionHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

// 클래스로 분리한 이유: Job 설정(Config)과 별개로 Mockito 단위 테스트가 가능해야 함
// (프로젝트 규칙 — 컨테이너 없이 검증 가능한 로직은 단위 테스트로 커버).
@Component
@RequiredArgsConstructor
public class BalanceReconciliationProcessor implements ItemProcessor<Account, BalanceMismatch> {

    private final TransactionHistoryRepository transactionHistoryRepository;

    @Override
    public BalanceMismatch process(Account account) {
        // Current balance and lifetime ledger sum share the chunk's REPEATABLE READ snapshot.
        // scheduleDate controls when to run; it is deliberately not a ledger cutoff.
        BigDecimal expected = transactionHistoryRepository.calculateNetAmount(account.getId());
        BigDecimal actual = account.getBalance();
        // 일치하면 null 반환 -> Spring Batch가 이 아이템을 writer로 넘기지 않고 그냥 건너뜀
        return actual.compareTo(expected) == 0 ? null : new BalanceMismatch(account.getId(), account.getAccountNumber(), actual, expected);
    }
}
