package com.trading.account.domain.account;

import com.trading.account.common.exception.CustomException;
import com.trading.account.common.exception.ErrorCode;
import com.trading.account.domain.account.dto.AccountBalanceResDto;
import com.trading.account.domain.account.dto.AccountCreateResDto;
import com.trading.account.domain.member.Member;
import com.trading.account.domain.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Transactional
@RequiredArgsConstructor
public class AccountService {

    private static final int ACCOUNT_NUMBER_GENERATION_RETRY = 5;

    private final AccountRepository accountRepository;
    private final MemberRepository memberRepository;

    public AccountCreateResDto createAccount(Long requesterId) {
        // 계좌는 항상 로그인한 본인 명의로만 개설 — 클라이언트가 memberId를 지정할 수 없도록
        // 아예 요청 파라미터에서 없애고 인증된 requesterId만 사용한다 (IS-17)
        Member member = memberRepository.findById(requesterId)
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
    public AccountBalanceResDto getBalance(String accountNumber, Long requesterId) {
        Account account = getAccount(accountNumber);
        validateOwner(account, requesterId);

        return new AccountBalanceResDto(account.getAccountNumber(), account.getBalance());
    }

    // 계좌 조회+존재검증은 Account 도메인 책임 — TransactionService 등 다른 서비스에서도 사용 (public)
    public Account getAccount(String accountNumber) {
        if (!AccountNumberValidator.isValid(accountNumber)) {
            throw new CustomException(ErrorCode.INVALID_ACCOUNT_NUMBER);
        }
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
    }

    // 인증(로그인 여부)과 별개로, 로그인한 사용자가 "이 계좌"의 진짜 소유자인지 확인 (IS-17)
    public void validateOwner(Account account, Long requesterId) {
        if (!Objects.equals(account.getMember().getId(), requesterId)) {
            throw new CustomException(ErrorCode.ACCESS_DENIED);
        }
    }

    private String generateAccountNumber() {
        for (int i = 0; i < ACCOUNT_NUMBER_GENERATION_RETRY; i++) {
            String digitsOnly = String.format("%03d%03d%04d",
                    ThreadLocalRandom.current().nextInt(1000),
                    ThreadLocalRandom.current().nextInt(1000),
                    ThreadLocalRandom.current().nextInt(10000));
            String withCheckDigit = AccountNumberValidator.appendCheckDigit(digitsOnly);
            String candidate = withCheckDigit.substring(0, 3) + "-" + withCheckDigit.substring(3, 6) + "-" + withCheckDigit.substring(6);
            if (!accountRepository.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
    }
}
