package com.trading.account.domain.transaction;

import com.trading.account.AbstractIntegrationTest;
import com.trading.account.common.exception.CustomException;
import com.trading.account.domain.account.AccountService;
import com.trading.account.domain.member.Member;
import com.trading.account.domain.member.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "ledger.simulated-deposits-enabled=false")
class SimulatedDepositDisabledTest extends AbstractIntegrationTest {
    @Autowired MemberRepository members;
    @Autowired AccountService accounts;
    @Autowired TransactionService transactions;

    @Test
    void regularCallerCannotCreateMoneyWhenSimulationIsDisabled() {
        Member member = members.save(new Member("Test", "disabled@test.invalid", "test-hash"));
        String account = accounts.createAccount(member.getId()).accountNumber();
        assertThatThrownBy(() -> transactions.deposit(account, member.getId(), BigDecimal.ONE))
                .isInstanceOf(CustomException.class);
    }
}
