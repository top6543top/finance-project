package com.trading.account.domain.account;

import com.trading.account.common.exception.CustomException;
import com.trading.account.common.exception.ErrorCode;
import com.trading.account.domain.account.dto.AccountBalanceResDto;
import com.trading.account.domain.account.dto.AccountCreateReqDto;
import com.trading.account.domain.account.dto.AccountCreateResDto;
import com.trading.account.domain.member.Member;
import com.trading.account.domain.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ThreadLocalRandom;

@Service
@Transactional
@RequiredArgsConstructor
public class AccountService {

    private static final int ACCOUNT_NUMBER_GENERATION_RETRY = 5;

    private final AccountRepository accountRepository;
    private final MemberRepository memberRepository;

    public AccountCreateResDto createAccount(AccountCreateReqDto request) {
        Member member = memberRepository.findById(request.memberId())
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        Account account;
        try {
            account = accountRepository.save(new Account(generateAccountNumber(), member));
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.DUPLICATE_RESOURCE);
        }

        return new AccountCreateResDto(
                account.getId(), account.getAccountNumber(), account.getBalance(),
                member.getId(), account.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public AccountBalanceResDto getBalance(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));

        return new AccountBalanceResDto(account.getAccountNumber(), account.getBalance());
    }

    private String generateAccountNumber() {
        for (int i = 0; i < ACCOUNT_NUMBER_GENERATION_RETRY; i++) {
            String candidate = String.format("%03d-%03d-%04d",
                    ThreadLocalRandom.current().nextInt(1000),
                    ThreadLocalRandom.current().nextInt(1000),
                    ThreadLocalRandom.current().nextInt(10000));
            if (!accountRepository.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
    }
}
