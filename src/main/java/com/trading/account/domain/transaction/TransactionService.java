package com.trading.account.domain.transaction;

import com.trading.account.common.exception.CustomException;
import com.trading.account.common.exception.ErrorCode;
import com.trading.account.domain.account.Account;
import com.trading.account.domain.account.AccountRepository;
import com.trading.account.domain.account.AccountService;
import com.trading.account.domain.account.MoneyAmount;
import com.trading.account.domain.idempotency.IdempotencyKey;
import com.trading.account.domain.transaction.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class TransactionService {
    private final AccountRepository accountRepository;
    private final TransactionHistoryRepository transactionHistoryRepository;
    private final AccountService accountService;

    @Value("${ledger.simulated-deposits-enabled:false}")
    private boolean simulatedDepositsEnabled;

    // HTTP mutations join IdempotencyService's transaction. They never retry inside that transaction.
    // Three-argument methods support internal callers; those callers own their transaction/retry boundary.
    public TransactionResDto deposit(String accountNumber, Long requesterId, BigDecimal amount) {
        return deposit(accountNumber, requesterId, amount, null);
    }

    public TransactionResDto deposit(String accountNumber, Long requesterId, BigDecimal amount, IdempotencyKey claim) {
        if (!simulatedDepositsEnabled) throw new CustomException(ErrorCode.SIMULATED_DEPOSIT_DISABLED);
        amount = MoneyAmount.requireValid(amount);
        Account account = accountService.getAccount(accountNumber);
        accountService.validateOwner(account, requesterId);
        increaseBalance(account, amount);
        TransactionHistory history = record(null, account, amount, TransactionType.DEPOSIT, requesterId, claim);
        return toResponse(account, amount, history);
    }

    public TransactionResDto withdraw(String accountNumber, Long requesterId, BigDecimal amount) {
        return withdraw(accountNumber, requesterId, amount, null);
    }

    public TransactionResDto withdraw(String accountNumber, Long requesterId, BigDecimal amount, IdempotencyKey claim) {
        amount = MoneyAmount.requireValid(amount);
        Account account = accountService.getAccount(accountNumber);
        accountService.validateOwner(account, requesterId);
        decreaseBalanceIfEnough(account, amount);
        TransactionHistory history = record(account, null, amount, TransactionType.WITHDRAW, requesterId, claim);
        return toResponse(account, amount, history);
    }

    public TransferResDto transfer(TransferReqDto request, Long requesterId) {
        return transfer(request, requesterId, null);
    }

    public TransferResDto transfer(TransferReqDto request, Long requesterId, IdempotencyKey claim) {
        BigDecimal amount = MoneyAmount.requireValid(request.amount());
        if (request.fromAccountNumber() == null || request.toAccountNumber() == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
        }
        if (request.fromAccountNumber().equals(request.toAccountNumber())) {
            throw new CustomException(ErrorCode.SELF_TRANSFER_NOT_ALLOWED);
        }
        boolean fromFirst = request.fromAccountNumber().compareTo(request.toAccountNumber()) < 0;
        Account first = accountService.getAccount(fromFirst ? request.fromAccountNumber() : request.toAccountNumber());
        Account second = accountService.getAccount(fromFirst ? request.toAccountNumber() : request.fromAccountNumber());
        Account from = fromFirst ? first : second;
        Account to = fromFirst ? second : first;
        accountService.validateOwner(from, requesterId);

        // Conditional UPDATE locks the row and checks its latest balance. Use one global order for both directions.
        if (fromFirst) {
            decreaseBalanceIfEnough(from, amount);
            increaseBalance(to, amount);
        } else {
            increaseBalance(to, amount);
            decreaseBalanceIfEnough(from, amount);
        }

        TransactionHistory history = record(from, to, amount, TransactionType.TRANSFER, requesterId, claim);
        return new TransferResDto(from.getAccountNumber(), to.getAccountNumber(), amount, history.getCreatedAt(),
                history.getId(), requesterId, history.getIdempotencyId());
    }

    @Transactional(readOnly = true)
    public Page<TransactionHistoryResDto> getHistory(String accountNumber, Long requesterId, Pageable pageable) {
        validatePageSize(pageable.getPageSize());
        Account account = accountService.getAccount(accountNumber);
        accountService.validateOwner(account, requesterId);
        // Fixed order is part of the API contract; client sort expressions cannot change the unique order.
        return transactionHistoryRepository.findByAccount(account,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize())).map(TransactionHistoryResDto::from);
    }

    @Transactional(readOnly = true)
    public TransactionHistoryCursorResDto getHistoryCursor(String accountNumber, Long requesterId, Long beforeId, int size) {
        validatePageSize(size);
        if (beforeId != null && beforeId <= 0) throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
        Account account = accountService.getAccount(accountNumber);
        accountService.validateOwner(account, requesterId);
        var rows = transactionHistoryRepository.findByAccountBeforeId(account, beforeId, PageRequest.of(0, size + 1));
        boolean hasNext = rows.size() > size;
        var items = rows.stream().limit(size).map(TransactionHistoryResDto::from).toList();
        Long nextCursor = hasNext ? items.get(items.size() - 1).id() : null;
        return new TransactionHistoryCursorResDto(items, nextCursor, hasNext);
    }

    private void validatePageSize(int size) {
        if (size < 1 || size > 100) throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
    }

    private void increaseBalance(Account account, BigDecimal amount) {
        if (accountRepository.increaseBalance(account.getId(), amount) != 1) {
            throw new CustomException(ErrorCode.BALANCE_LIMIT_EXCEEDED);
        }
    }

    private void decreaseBalanceIfEnough(Account account, BigDecimal amount) {
        if (accountRepository.decreaseBalanceIfEnough(account.getId(), amount) != 1) {
            throw new CustomException(ErrorCode.INSUFFICIENT_BALANCE);
        }
    }

    private TransactionHistory record(Account from, Account to, BigDecimal amount, TransactionType type,
                                      Long actorId, IdempotencyKey claim) {
        TransactionHistory history = transactionHistoryRepository.save(new TransactionHistory(from, to, amount, type, actorId, claim));
        // The durable ledger survives a process crash; operational success logs must only describe committed rows.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            Long transactionId = history.getId();
            Long idempotencyId = history.getIdempotencyId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    log.info("Transaction committed: transactionId={}, actorId={}, operation={}, idempotencyId={}",
                            transactionId, actorId, type, idempotencyId);
                }
            });
        }
        return history;
    }

    private TransactionResDto toResponse(Account account, BigDecimal amount, TransactionHistory history) {
        BigDecimal balance = accountRepository.findBalanceById(account.getId())
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
        return new TransactionResDto(account.getAccountNumber(), balance, amount, history.getType(), history.getCreatedAt(),
                history.getId(), history.getActorId(), history.getIdempotencyId());
    }
}
