package com.trading.account.domain.account;

import com.trading.account.domain.member.Member;
import com.trading.account.domain.member.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AccountRepositoryTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Test
    void save_persistsAccountWithZeroBalanceAndVersion() {
        Member member = memberRepository.save(new Member("김유현", "yuhyun@example.com", "password123!"));

        Account saved = accountRepository.save(new Account("123-456-7890", member));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(saved.getVersion()).isNotNull();
    }

    @Test
    void findByAccountNumber_returnsAccount() {
        Member member = memberRepository.save(new Member("김유현", "yuhyun@example.com", "password123!"));
        accountRepository.save(new Account("111-222-3333", member));

        assertThat(accountRepository.findByAccountNumber("111-222-3333")).isPresent();
        assertThat(accountRepository.findByAccountNumber("nope")).isEmpty();
    }

    @Test
    void decreaseBalanceIfEnough_updatesOnlyWhenBalanceIsEnough() {
        Member member = memberRepository.save(new Member("김유현", "atomic@example.com", "password123!"));
        Account account = accountRepository.save(new Account("333-333-33335", member));
        accountRepository.increaseBalance(account.getId(), BigDecimal.valueOf(1000));

        int updated = accountRepository.decreaseBalanceIfEnough(account.getId(), BigDecimal.valueOf(400));
        int rejected = accountRepository.decreaseBalanceIfEnough(account.getId(), BigDecimal.valueOf(9999));

        assertThat(updated).isEqualTo(1);
        assertThat(rejected).isZero();
        assertThat(accountRepository.findBalanceById(account.getId()))
                .hasValueSatisfying(balance -> assertThat(balance).isEqualByComparingTo(BigDecimal.valueOf(600)));
    }
}
