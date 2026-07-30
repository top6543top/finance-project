package com.trading.account.domain.transaction;

import com.trading.account.common.exception.CustomException;
import com.trading.account.common.exception.ErrorCode;
import com.trading.account.domain.account.Account;
import com.trading.account.domain.account.AccountRepository;
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
import java.util.Objects;

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
    public TransactionResDto deposit(String accountNumber, Long requesterId, BigDecimal amount) {
        Account account = getAccount(accountNumber);
        validateOwner(account, requesterId);
        account.deposit(amount);
        flushBeforeHistoryInsert();
        TransactionHistory history = transactionHistoryRepository.save(
                new TransactionHistory(null, account, amount, TransactionType.DEPOSIT));
        log.info("입금 완료: account={}, amount={}", mask(accountNumber), amount);
        return toTransactionResDto(account, amount, TransactionType.DEPOSIT, history.getCreatedAt());
    }

    @Retryable(retryFor = ConcurrencyFailureException.class, maxAttempts = MAX_RETRY_ATTEMPTS,
            backoff = @Backoff(delay = 50, multiplier = 2, random = true))
    public TransactionResDto withdraw(String accountNumber, Long requesterId, BigDecimal amount) {
        Account account = getAccount(accountNumber);
        validateOwner(account, requesterId);
        account.withdraw(amount);
        flushBeforeHistoryInsert();
        TransactionHistory history = transactionHistoryRepository.save(
                new TransactionHistory(account, null, amount, TransactionType.WITHDRAW));
        log.info("출금 완료: account={}, amount={}", mask(accountNumber), amount);
        return toTransactionResDto(account, amount, TransactionType.WITHDRAW, history.getCreatedAt());
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
        Account first = getAccount(fromFirst ? request.fromAccountNumber() : request.toAccountNumber());
        Account second = getAccount(fromFirst ? request.toAccountNumber() : request.fromAccountNumber());
        Account from = fromFirst ? first : second;
        Account to = fromFirst ? second : first;

        // 이체는 출금 계좌(from)만 요청자 소유인지 검증 — 입금 계좌(to)는 상대방 소유가 당연하므로 검증 대상 아님 (IS-17)
        validateOwner(from, requesterId);

        from.withdraw(request.amount());
        to.deposit(request.amount());
        flushBeforeHistoryInsert();
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
        Account account = getAccount(accountNumber);
        validateOwner(account, requesterId);
        return transactionHistoryRepository.findByAccount(account, pageable)
                .map(TransactionHistoryResDto::from);
    }

    private Account getAccount(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
    }

    // 인증(로그인 여부)과 별개로, 로그인한 사용자가 "이 계좌"의 진짜 소유자인지 확인 (IS-17)
    private void validateOwner(Account account, Long requesterId) {
        if (!Objects.equals(account.getMember().getId(), requesterId)) {
            throw new CustomException(ErrorCode.ACCESS_DENIED);
        }
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
        if (accountNumber.length() < 4) {
            return "***";
        }
        return "***-***-" + accountNumber.substring(accountNumber.length() - 4);
    }
}
