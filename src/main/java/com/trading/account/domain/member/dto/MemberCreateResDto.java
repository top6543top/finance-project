package com.trading.account.domain.member.dto;

import java.time.LocalDateTime;

public record MemberCreateResDto(
        Long id,
        String name,
        String email,
        LocalDateTime createdAt
) {
}
