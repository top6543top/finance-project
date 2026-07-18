package com.trading.account.domain.transaction;

import com.trading.account.domain.account.Account;
import com.trading.account.domain.account.AccountRepository;
import com.trading.account.domain.member.Member;
import com.trading.account.domain.member.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class TransactionHistoryRepositoryTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Autowired
    private TransactionHistoryRepository transactionHistoryRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Test
    void findByAccount_returnsDepositsAndWithdrawsAndTransfersInvolvingAccount() {
        Member member = memberRepository.save(new Member("김유현", "yuhyun@example.com"));
        Account a = accountRepository.save(new Account("111-111-1111", member));
        Account b = accountRepository.save(new Account("222-222-2222", member));

        transactionHistoryRepository.save(new TransactionHistory(null, a, BigDecimal.TEN, TransactionType.DEPOSIT));
        transactionHistoryRepository.save(new TransactionHistory(a, null, BigDecimal.ONE, TransactionType.WITHDRAW));
        transactionHistoryRepository.save(new TransactionHistory(a, b, BigDecimal.valueOf(5), TransactionType.TRANSFER));
        // a와 무관한 거래는 결과에 포함되지 않아야 함
        transactionHistoryRepository.save(new TransactionHistory(null, b, BigDecimal.valueOf(2), TransactionType.DEPOSIT));

        var page = transactionHistoryRepository.findByAccount(a, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(3);
    }
}
