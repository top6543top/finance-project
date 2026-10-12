package com.trading.account.domain.reconciliation;

import com.trading.account.domain.account.Account;
import com.trading.account.domain.member.Member;
import com.trading.account.domain.transaction.TransactionHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BalanceReconciliationProcessorTest {

    @Mock
    private TransactionHistoryRepository transactionHistoryRepository;

    @InjectMocks
    private BalanceReconciliationProcessor processor;

    @Test
    void process_balanceMatchesHistory_returnsNull() throws Exception {
        Account account = accountWithId(1L, "123-456-78903", BigDecimal.valueOf(1000));
        when(transactionHistoryRepository.calculateNetAmount(1L)).thenReturn(BigDecimal.valueOf(1000));

        BalanceMismatch result = processor.process(account);

        assertThat(result).isNull();
    }

    @Test
    void process_balanceDiffersFromHistory_returnsMismatch() throws Exception {
        Account account = accountWithId(2L, "123-456-78911", BigDecimal.valueOf(1000));
        when(transactionHistoryRepository.calculateNetAmount(2L)).thenReturn(BigDecimal.valueOf(700));

        BalanceMismatch result = processor.process(account);

        assertThat(result).isNotNull();
        assertThat(result.accountId()).isEqualTo(2L);
        assertThat(result.accountNumber()).isEqualTo("123-456-78911");
        assertThat(result.actualBalance()).isEqualByComparingTo(BigDecimal.valueOf(1000));
        assertThat(result.expectedBalance()).isEqualByComparingTo(BigDecimal.valueOf(700));
    }

    // Account.id는 @GeneratedValue라 생성자로 못 넣는다. 리포지토리 왕복 없이 순수 단위
    // 테스트로 유지하기 위해 리플렉션으로 id만 채운다.
    private Account accountWithId(Long id, String accountNumber, BigDecimal balance) throws Exception {
        Member member = new Member("김유현", "yuhyun@example.com", "password123!");
        Account account = new Account(accountNumber, member);
        account.deposit(balance);
        Field idField = Account.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(account, id);
        return account;
    }
}
