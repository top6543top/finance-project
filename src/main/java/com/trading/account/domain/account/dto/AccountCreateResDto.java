package com.trading.account.domain.account.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AccountCreateResDto(
        Long id,
        String accountNumber,
        BigDecimal balance,
        Long memberId,
        LocalDateTime createdAt
) {
}
