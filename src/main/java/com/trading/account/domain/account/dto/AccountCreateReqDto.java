package com.trading.account.domain.account.dto;

import jakarta.validation.constraints.NotNull;

public record AccountCreateReqDto(
        @NotNull(message = "회원 ID는 필수입니다.") Long memberId
) {
}
