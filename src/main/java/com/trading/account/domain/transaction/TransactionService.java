package com.trading.account.domain.transaction;

import com.trading.account.common.exception.CustomException;
import com.trading.account.common.exception.ErrorCode;
import com.trading.account.domain.account.Account;
import com.trading.account.domain.account.AccountRepository;
import com.trading.account.domain.account.AccountService;
import com.trading.account.domain.transaction.dto.TransactionHistoryResDto;
import com.trading.account.domain.transaction.dto.TransactionResDto;
import com.trading.account.domain.transaction.dto.TransferReqDto;
import com.trading.account.domain.transaction.dto.TransferResDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.transaction.annotation.Isolation;
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
    private final AccountService accountService;

    @Retryable(retryFor = ConcurrencyFailureException.class, maxAttempts = MAX_RETRY_ATTEMPTS,
            backoff = @Backoff(delay = 50, multiplier = 2, random = true))
    public TransactionResDto deposit(String accountNumber, Long requesterId, BigDecimal amount) {
        Account account = accountService.getAccount(accountNumber);
        accountService.validateOwner(account, requesterId);
        increaseBalance(account, amount);
        TransactionHistory history = transactionHistoryRepository.save(
                new TransactionHistory(null, account, amount, TransactionType.DEPOSIT));
        log.info("입금 완료: account={}, amount={}", mask(accountNumber), amount);
        return toTransactionResDto(account, currentBalance(account), amount, TransactionType.DEPOSIT, history.getCreatedAt());
    }

    @Retryable(retryFor = ConcurrencyFailureException.class, maxAttempts = MAX_RETRY_ATTEMPTS,
            backoff = @Backoff(delay = 50, multiplier = 2, random = true))
    public TransactionResDto withdraw(String accountNumber, Long requesterId, BigDecimal amount) {
        Account account = accountService.getAccount(accountNumber);
        accountService.validateOwner(account, requesterId);
        decreaseBalanceIfEnough(account, amount);
        TransactionHistory history = transactionHistoryRepository.save(
                new TransactionHistory(account, null, amount, TransactionType.WITHDRAW));
        log.info("출금 완료: account={}, amount={}", mask(accountNumber), amount);
        return toTransactionResDto(account, currentBalance(account), amount, TransactionType.WITHDRAW, history.getCreatedAt());
    }

    @Recover
    public TransactionResDto recoverDepositOrWithdraw(ConcurrencyFailureException e, String accountNumber, Long requesterId, BigDecimal amount) {
        log.warn("동시성 충돌로 {}회 재시도 후에도 처리 실패: account={}", MAX_RETRY_ATTEMPTS, mask(accountNumber));
        throw new CustomException(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
    }

    // Spring Retry는 retryFor에 해당 안 되는 예외라도 같은 메서드에 @Recover가 하나라도
    // 있으면 recovery 매칭을 시도하고, 매칭되는 @Recover가 없으면 ExhaustedRetryException으로
    // 감싸버린다. ACCOUNT_NOT_FOUND/INSUFFICIENT_BALANCE 같은 CustomException(비즈니스 예외,
    // 재시도 대상 아님)이 그렇게 원래 에러코드를 잃고 500으로 새는 걸 막기 위해 그대로 다시 던진다.
    @Recover
    public TransactionResDto recoverBusinessException(CustomException e, String accountNumber, Long requesterId, BigDecimal amount) {
        throw e;
    }

    // 이체는 두 계좌를 함께 다루는 원자적 작업이라 격리 수준을 REPEATABLE_READ로 명시:
    // 이체 도중 다른 트랜잭션이 두 계좌 중 하나를 갱신해도 이 트랜잭션 내에서는
    // 처음 조회한 잔고 값 기준으로 일관되게 차감/증액하도록 보장
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    @Retryable(retryFor = ConcurrencyFailureException.class, maxAttempts = MAX_RETRY_ATTEMPTS,
            backoff = @Backoff(delay = 50, multiplier = 2, random = true))
    public TransferResDto transfer(TransferReqDto request, Long requesterId) {
        if (request.fromAccountNumber().equals(request.toAccountNumber())) {
            throw new CustomException(ErrorCode.SELF_TRANSFER_NOT_ALLOWED);
        }

        // A→B 이체와 B→A 이체가 동시에 일어나면 각자 "요청에 적힌 from 먼저, to 나중" 순서로
        // X-lock을 잡으려다 서로 반대 방향으로 물려 데드락이 난다(lock ordering deadlock,
        // flushBeforeHistoryInsert()가 고친 S-lock/X-lock 문제와는 별개의 원인).
        // 계좌번호 문자열 비교로 항상 같은 전역 순서로 조회해서 flush 순서를 고정하면,
        // 이체 방향과 무관하게 모든 트랜잭션이 같은 순서로만 잠그게 되어 순환 대기 자체가 사라진다.
        boolean fromFirst = request.fromAccountNumber().compareTo(request.toAccountNumber()) < 0;
        Account first = accountService.getAccount(fromFirst ? request.fromAccountNumber() : request.toAccountNumber());
        Account second = accountService.getAccount(fromFirst ? request.toAccountNumber() : request.fromAccountNumber());
        Account from = fromFirst ? first : second;
        Account to = fromFirst ? second : first;

        // 이체는 출금 계좌(from)만 요청자 소유인지 검증 — 입금 계좌(to)는 상대방 소유가 당연하므로 검증 대상 아님 (IS-17)
        accountService.validateOwner(from, requesterId);


        if (fromFirst) {
            decreaseBalanceIfEnough(from, request.amount());
            increaseBalance(to, request.amount());
        } else {
            increaseBalance(to, request.amount());
            decreaseBalanceIfEnough(from, request.amount());
        }

        TransactionHistory history = transactionHistoryRepository.save(
                new TransactionHistory(from, to, request.amount(), TransactionType.TRANSFER));

        log.info("이체 완료: from={}, to={}, amount={}", mask(from.getAccountNumber()), mask(to.getAccountNumber()), request.amount());
        return new TransferResDto(from.getAccountNumber(), to.getAccountNumber(), request.amount(), history.getCreatedAt());
    }

    @Recover
    public TransferResDto recoverTransfer(ConcurrencyFailureException e, TransferReqDto request, Long requesterId) {
        log.warn("동시성 충돌로 {}회 재시도 후에도 이체 실패: from={}, to={}",
                MAX_RETRY_ATTEMPTS, mask(request.fromAccountNumber()), mask(request.toAccountNumber()));
        throw new CustomException(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
    }

    // 위 recoverBusinessException()과 같은 이유: transfer()의 SELF_TRANSFER_NOT_ALLOWED/
    // ACCOUNT_NOT_FOUND 같은 CustomException도 재시도 대상이 아니므로 그대로 다시 던진다.
    @Recover
    public TransferResDto recoverTransferBusinessException(CustomException e, TransferReqDto request, Long requesterId) {
        throw e;
    }

    @Transactional(readOnly = true)
    public Page<TransactionHistoryResDto> getHistory(String accountNumber, Long requesterId, Pageable pageable) {
        Account account = accountService.getAccount(accountNumber);
        accountService.validateOwner(account, requesterId);
        return transactionHistoryRepository.findByAccount(account, pageable)
                .map(TransactionHistoryResDto::from);
    }

    private void increaseBalance(Account account, BigDecimal amount) {
        int updated = accountRepository.increaseBalance(account.getId(), amount);
        if (updated != 1) {
            throw new CustomException(ErrorCode.ACCOUNT_NOT_FOUND);
        }
    }

    private void decreaseBalanceIfEnough(Account account, BigDecimal amount) {
        int updated = accountRepository.decreaseBalanceIfEnough(account.getId(), amount);
        if (updated != 1) {
            throw new CustomException(ErrorCode.INSUFFICIENT_BALANCE);
        }
    }

    private BigDecimal currentBalance(Account account) {
        return accountRepository.findBalanceById(account.getId())
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
    }

    private TransactionResDto toTransactionResDto(Account account, BigDecimal balance, BigDecimal amount, TransactionType type, LocalDateTime createdAt) {
        return new TransactionResDto(account.getAccountNumber(), balance, amount, type, createdAt);
    }

    // 계좌번호 뒷자리만 남기고 마스킹 (금융 도메인 로그에 전체 계좌번호 노출 금지)
    private String mask(String accountNumber) {
        if (accountNumber.length() < 4) {
            return "***";
        }
        return "***-***-" + accountNumber.substring(accountNumber.length() - 4);
    }
}
