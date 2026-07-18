package com.trading.account.domain.transaction;

import com.trading.account.common.exception.CustomException;
import com.trading.account.common.exception.ErrorCode;
import com.trading.account.domain.account.Account;
import com.trading.account.domain.account.AccountRepository;
import com.trading.account.domain.member.Member;
import com.trading.account.domain.transaction.dto.TransactionResDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionHistoryRepository transactionHistoryRepository;

    @InjectMocks
    private TransactionService transactionService;

    private Account newAccount(String accountNumber) {
        Member member = new Member("김유현", "yuhyun@example.com");
        Account account = new Account(accountNumber, member);
        account.deposit(BigDecimal.valueOf(1000));
        return account;
    }

    @Test
    void deposit_success_increasesBalance() {
        Account account = newAccount("123-456-7890");
        when(accountRepository.findByAccountNumber("123-456-7890")).thenReturn(Optional.of(account));
        when(transactionHistoryRepository.save(any(TransactionHistory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransactionResDto response = transactionService.deposit("123-456-7890", BigDecimal.valueOf(500));

        assertThat(response.balance()).isEqualByComparingTo(BigDecimal.valueOf(1500));
    }

    @Test
    void withdraw_sufficientBalance_decreasesBalance() {
        Account account = newAccount("123-456-7890");
        when(accountRepository.findByAccountNumber("123-456-7890")).thenReturn(Optional.of(account));
        when(transactionHistoryRepository.save(any(TransactionHistory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransactionResDto response = transactionService.withdraw("123-456-7890", BigDecimal.valueOf(400));

        assertThat(response.balance()).isEqualByComparingTo(BigDecimal.valueOf(600));
    }

    @Test
    void withdraw_insufficientBalance_throwsCustomException() {
        Account account = newAccount("123-456-7890");
        when(accountRepository.findByAccountNumber("123-456-7890")).thenReturn(Optional.of(account));

        CustomException exception = catchThrowableOfType(
                () -> transactionService.withdraw("123-456-7890", BigDecimal.valueOf(9999)), CustomException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);
    }

    @Test
    void deposit_accountNotFound_throwsCustomException() {
        when(accountRepository.findByAccountNumber("000")).thenReturn(Optional.empty());

        CustomException exception = catchThrowableOfType(
                () -> transactionService.deposit("000", BigDecimal.valueOf(100)), CustomException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
    }
}
