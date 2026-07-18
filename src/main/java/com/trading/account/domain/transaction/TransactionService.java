package com.trading.account.domain.transaction;

import com.trading.account.common.exception.CustomException;
import com.trading.account.common.exception.ErrorCode;
import com.trading.account.domain.account.Account;
import com.trading.account.domain.account.AccountRepository;
import com.trading.account.domain.transaction.dto.TransactionResDto;
import com.trading.account.domain.transaction.dto.TransferReqDto;
import com.trading.account.domain.transaction.dto.TransferResDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final AccountRepository accountRepository;
    private final TransactionHistoryRepository transactionHistoryRepository;

    public TransactionResDto deposit(String accountNumber, BigDecimal amount) {
        Account account = getAccount(accountNumber);
        account.deposit(amount);
        TransactionHistory history = transactionHistoryRepository.save(
                new TransactionHistory(null, account, amount, TransactionType.DEPOSIT));
        log.info("입금 완료: account={}, amount={}", mask(accountNumber), amount);
        return toTransactionResDto(account, amount, TransactionType.DEPOSIT, history.getCreatedAt());
    }

    public TransactionResDto withdraw(String accountNumber, BigDecimal amount) {
        Account account = getAccount(accountNumber);
        account.withdraw(amount);
        TransactionHistory history = transactionHistoryRepository.save(
                new TransactionHistory(account, null, amount, TransactionType.WITHDRAW));
        log.info("출금 완료: account={}, amount={}", mask(accountNumber), amount);
        return toTransactionResDto(account, amount, TransactionType.WITHDRAW, history.getCreatedAt());
    }

    // 이체는 두 계좌를 함께 다루는 원자적 작업이라 격리 수준을 REPEATABLE_READ로 명시:
    // 이체 도중 다른 트랜잭션이 두 계좌 중 하나를 갱신해도 이 트랜잭션 내에서는
    // 처음 조회한 잔고 값 기준으로 일관되게 차감/증액하도록 보장
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public TransferResDto transfer(TransferReqDto request) {
        if (request.fromAccountNumber().equals(request.toAccountNumber())) {
            throw new CustomException(ErrorCode.SELF_TRANSFER_NOT_ALLOWED);
        }

        Account from = getAccount(request.fromAccountNumber());
        Account to = getAccount(request.toAccountNumber());
        from.withdraw(request.amount());
        to.deposit(request.amount());
        TransactionHistory history = transactionHistoryRepository.save(
                new TransactionHistory(from, to, request.amount(), TransactionType.TRANSFER));

        log.info("이체 완료: from={}, to={}, amount={}", mask(from.getAccountNumber()), mask(to.getAccountNumber()), request.amount());
        return new TransferResDto(from.getAccountNumber(), to.getAccountNumber(), request.amount(), history.getCreatedAt());
    }

    private Account getAccount(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
    }

    private TransactionResDto toTransactionResDto(Account account, BigDecimal amount, TransactionType type, LocalDateTime createdAt) {
        return new TransactionResDto(account.getAccountNumber(), account.getBalance(), amount, type, createdAt);
    }

    // 계좌번호 뒷자리만 남기고 마스킹 (금융 도메인 로그에 전체 계좌번호 노출 금지)
    private String mask(String accountNumber) {
        return "***-***-" + accountNumber.substring(accountNumber.length() - 4);
    }
}
