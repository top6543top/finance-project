package com.trading.account.domain.account;

import com.trading.account.common.exception.CustomException;
import com.trading.account.common.exception.ErrorCode;
import com.trading.account.domain.account.dto.AccountBalanceResDto;
import com.trading.account.domain.account.dto.AccountCreateReqDto;
import com.trading.account.domain.account.dto.AccountCreateResDto;
import com.trading.account.domain.member.Member;
import com.trading.account.domain.member.MemberRepository;
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
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private MemberRepository memberRepository;

    @InjectMocks
    private AccountService accountService;

    @Test
    void createAccount_success_returnsAccountWithZeroBalance() {
        Member member = new Member("김유현", "yuhyun@example.com", "password123!");
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(accountRepository.existsByAccountNumber(any())).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccountCreateResDto response = accountService.createAccount(new AccountCreateReqDto(1L));

        assertThat(response.accountNumber()).isNotBlank();
        assertThat(response.balance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void createAccount_memberNotFound_throwsCustomException() {
        when(memberRepository.findById(1L)).thenReturn(Optional.empty());

        CustomException exception = catchThrowableOfType(
                () -> accountService.createAccount(new AccountCreateReqDto(1L)), CustomException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    void getBalance_found_returnsBalance() {
        Member member = new Member("김유현", "yuhyun@example.com", "password123!");
        Account account = new Account("123-456-7890", member);
        when(accountRepository.findByAccountNumber("123-456-7890")).thenReturn(Optional.of(account));

        AccountBalanceResDto response = accountService.getBalance("123-456-7890");

        assertThat(response.accountNumber()).isEqualTo("123-456-7890");
        assertThat(response.balance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void getBalance_notFound_throwsCustomException() {
        when(accountRepository.findByAccountNumber("000")).thenReturn(Optional.empty());

        CustomException exception = catchThrowableOfType(
                () -> accountService.getBalance("000"), CustomException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
    }
}
