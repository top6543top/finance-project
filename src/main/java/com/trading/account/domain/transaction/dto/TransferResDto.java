package com.trading.account.domain.transaction.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransferResDto(
        String fromAccountNumber,
        String toAccountNumber,
        BigDecimal amount,
        LocalDateTime createdAt
) {
}
