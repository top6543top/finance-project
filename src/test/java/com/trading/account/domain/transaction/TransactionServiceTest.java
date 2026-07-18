package com.trading.account.domain.transaction;

import com.trading.account.common.exception.CustomException;
import com.trading.account.common.exception.ErrorCode;
import com.trading.account.domain.account.Account;
import com.trading.account.domain.account.AccountRepository;
import com.trading.account.domain.member.Member;
import com.trading.account.domain.transaction.dto.TransactionResDto;
import com.trading.account.domain.transaction.dto.TransferReqDto;
import com.trading.account.domain.transaction.dto.TransferResDto;
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
    void transfer_success_movesBalanceBetweenAccounts() {
        Account from = newAccount("111-111-1111");
        Account to = newAccount("222-222-2222");
        when(accountRepository.findByAccountNumber("111-111-1111")).thenReturn(Optional.of(from));
        when(accountRepository.findByAccountNumber("222-222-2222")).thenReturn(Optional.of(to));
        when(transactionHistoryRepository.save(any(TransactionHistory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransferResDto response = transactionService.transfer(
                new TransferReqDto("111-111-1111", "222-222-2222", BigDecimal.valueOf(300)));

        assertThat(response.fromAccountNumber()).isEqualTo("111-111-1111");
        assertThat(from.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(700));
        assertThat(to.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(1300));
    }

    @Test
    void transfer_sameAccount_throwsCustomException() {
        CustomException exception = catchThrowableOfType(
                () -> transactionService.transfer(
                        new TransferReqDto("111-111-1111", "111-111-1111", BigDecimal.valueOf(100))),
                CustomException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SELF_TRANSFER_NOT_ALLOWED);
    }

    @Test
    void deposit_accountNotFound_throwsCustomException() {
        when(accountRepository.findByAccountNumber("000")).thenReturn(Optional.empty());

        CustomException exception = catchThrowableOfType(
                () -> transactionService.deposit("000", BigDecimal.valueOf(100)), CustomException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
    }
}
