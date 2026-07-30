package com.trading.account.domain.auth.dto;

public record LoginResDto(
        String accessToken,
        String tokenType,
        long expiresInSeconds
) {
}
