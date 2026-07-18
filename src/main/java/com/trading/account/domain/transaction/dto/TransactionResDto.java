package com.trading.account.domain.transaction.dto;

import com.trading.account.domain.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionResDto(
        String accountNumber,
        BigDecimal balance,
        BigDecimal amount,
        TransactionType type,
        LocalDateTime createdAt
) {
}
