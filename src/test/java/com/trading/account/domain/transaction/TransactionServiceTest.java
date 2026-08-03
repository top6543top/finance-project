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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionHistoryRepository transactionHistoryRepository;

    @InjectMocks
    private TransactionService transactionService;

    private static final Long OWNER_ID = 1L;

    private Account newAccount(String accountNumber) {
        Member owner = mock(Member.class);
        when(owner.getId()).thenReturn(OWNER_ID);
        Account account = new Account(accountNumber, owner);
        account.deposit(BigDecimal.valueOf(1000));
        return account;
    }

    @Test
    void deposit_success_increasesBalance() {
        Account account = newAccount("123-456-78903");
        when(accountRepository.findByAccountNumber("123-456-78903")).thenReturn(Optional.of(account));
        when(transactionHistoryRepository.save(any(TransactionHistory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransactionResDto response = transactionService.deposit("123-456-78903", OWNER_ID, BigDecimal.valueOf(500));

        assertThat(response.balance()).isEqualByComparingTo(BigDecimal.valueOf(1500));
    }

    @Test
    void withdraw_sufficientBalance_decreasesBalance() {
        Account account = newAccount("123-456-78903");
        when(accountRepository.findByAccountNumber("123-456-78903")).thenReturn(Optional.of(account));
        when(transactionHistoryRepository.save(any(TransactionHistory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransactionResDto response = transactionService.withdraw("123-456-78903", OWNER_ID, BigDecimal.valueOf(400));

        assertThat(response.balance()).isEqualByComparingTo(BigDecimal.valueOf(600));
    }

    @Test
    void withdraw_insufficientBalance_throwsCustomException() {
        Account account = newAccount("123-456-78903");
        when(accountRepository.findByAccountNumber("123-456-78903")).thenReturn(Optional.of(account));

        CustomException exception = catchThrowableOfType(
                () -> transactionService.withdraw("123-456-78903", OWNER_ID, BigDecimal.valueOf(9999)), CustomException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);
    }

    @Test
    void deposit_accountNotFound_throwsCustomException() {
        when(accountRepository.findByAccountNumber("000-000-00000")).thenReturn(Optional.empty());

        CustomException exception = catchThrowableOfType(
                () -> transactionService.deposit("000-000-00000", OWNER_ID, BigDecimal.valueOf(100)), CustomException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    void deposit_invalidAccountNumberFormat_throwsCustomException() {
        CustomException exception = catchThrowableOfType(
                () -> transactionService.deposit("not-a-number", OWNER_ID, BigDecimal.valueOf(100)), CustomException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_ACCOUNT_NUMBER);
    }

    @Test
    void deposit_notOwner_throwsAccessDenied() {
        Account account = newAccount("123-456-78903");
        when(accountRepository.findByAccountNumber("123-456-78903")).thenReturn(Optional.of(account));

        CustomException exception = catchThrowableOfType(
                () -> transactionService.deposit("123-456-78903", 2L, BigDecimal.valueOf(100)), CustomException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED);
    }
}
