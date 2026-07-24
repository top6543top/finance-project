package com.trading.account.domain.transaction;

import com.trading.account.common.exception.CustomException;
import com.trading.account.common.exception.ErrorCode;
import com.trading.account.domain.account.Account;
import com.trading.account.domain.account.AccountRepository;
import com.trading.account.domain.transaction.dto.TransactionResDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class TransactionService {

    // 낙관적 락(@Version) 충돌과, REPEATABLE_READ 하에서 동시 UPDATE가 걸릴 때
    // MySQL이 던지는 데드락(Deadlock found ...)을 같은 정책으로 재시도한다.
    // 두 예외 모두 Spring이 org.springframework.dao.ConcurrencyFailureException 계열로
    // 번역해주므로 그 상위 타입 하나만 잡으면 됨. delay에 random을 줘서 재시도들이
    // 같은 타이밍에 다시 충돌(재데드락)하지 않도록 지터를 둔다.
    private static final int MAX_RETRY_ATTEMPTS = 5;

    private final AccountRepository accountRepository;
    private final TransactionHistoryRepository transactionHistoryRepository;

    @Retryable(retryFor = ConcurrencyFailureException.class, maxAttempts = MAX_RETRY_ATTEMPTS,
            backoff = @Backoff(delay = 50, multiplier = 2, random = true))
    public TransactionResDto deposit(String accountNumber, BigDecimal amount) {
        Account account = getAccount(accountNumber);
        account.deposit(amount);
        flushBeforeHistoryInsert();
        TransactionHistory history = transactionHistoryRepository.save(
                new TransactionHistory(null, account, amount, TransactionType.DEPOSIT));
        log.info("입금 완료: account={}, amount={}", mask(accountNumber), amount);
        return toTransactionResDto(account, amount, TransactionType.DEPOSIT, history.getCreatedAt());
    }

    @Retryable(retryFor = ConcurrencyFailureException.class, maxAttempts = MAX_RETRY_ATTEMPTS,
            backoff = @Backoff(delay = 50, multiplier = 2, random = true))
    public TransactionResDto withdraw(String accountNumber, BigDecimal amount) {
        Account account = getAccount(accountNumber);
        account.withdraw(amount);
        flushBeforeHistoryInsert();
        TransactionHistory history = transactionHistoryRepository.save(
                new TransactionHistory(account, null, amount, TransactionType.WITHDRAW));
        log.info("출금 완료: account={}, amount={}", mask(accountNumber), amount);
        return toTransactionResDto(account, amount, TransactionType.WITHDRAW, history.getCreatedAt());
    }

    @Recover
    public TransactionResDto recoverDepositOrWithdraw(ConcurrencyFailureException e, String accountNumber, BigDecimal amount) {
        log.warn("동시성 충돌로 {}회 재시도 후에도 처리 실패: account={}", MAX_RETRY_ATTEMPTS, mask(accountNumber));
        throw new CustomException(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
    }

    private Account getAccount(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
    }

    // TransactionHistory는 IDENTITY 채번이라 save() 시점에 즉시 INSERT가 나가고, FK 제약 검사 때문에
    // 참조되는 account 행에 공유 락(S-lock)을 먼저 잡는다. 반면 account.balance 변경은 더티체킹이라
    // 커밋 시점에야 UPDATE(배타 락, X-lock)가 나간다. 이 순서(S-lock 먼저, X-lock 나중)로 두면 두
    // 트랜잭션이 서로 상대의 S-lock 해제를 기다리며 X-lock으로 승격하려다 데드락이 난다(IS-12에서
    // 실제 관측). account UPDATE를 여기서 미리 flush해서 X-lock을 먼저 잡아버리면 이 승격 경쟁 자체가
    // 사라진다.
    private void flushBeforeHistoryInsert() {
        accountRepository.flush();
    }

    private TransactionResDto toTransactionResDto(Account account, BigDecimal amount, TransactionType type, LocalDateTime createdAt) {
        return new TransactionResDto(account.getAccountNumber(), account.getBalance(), amount, type, createdAt);
    }

    // 계좌번호 뒷자리만 남기고 마스킹 (금융 도메인 로그에 전체 계좌번호 노출 금지)
    private String mask(String accountNumber) {
        return "***-***-" + accountNumber.substring(accountNumber.length() - 4);
    }
}
