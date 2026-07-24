package com.trading.account.domain.transaction.dto;

import com.trading.account.domain.transaction.TransactionHistory;
import com.trading.account.domain.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionHistoryResDto(
        Long id,
        String fromAccountNumber,
        String toAccountNumber,
        BigDecimal amount,
        TransactionType type,
        LocalDateTime createdAt
) {
    public static TransactionHistoryResDto from(TransactionHistory history) {
        return new TransactionHistoryResDto(
                history.getId(),
                history.getFromAccount() != null ? history.getFromAccount().getAccountNumber() : null,
                history.getToAccount() != null ? history.getToAccount().getAccountNumber() : null,
                history.getAmount(),
                history.getType(),
                history.getCreatedAt()
        );
    }
}
