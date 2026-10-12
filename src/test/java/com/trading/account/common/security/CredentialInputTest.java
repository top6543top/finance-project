package com.trading.account.common.security;

import com.trading.account.domain.auth.dto.LoginReqDto;
import com.trading.account.domain.member.dto.MemberCreateReqDto;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CredentialInputTest {
    @Test void bcryptLimitCountsUtf8BytesForSignupAndLogin() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String password : new String[]{"a".repeat(73), "가".repeat(25)}) {
                assertThat(validator.validate(new MemberCreateReqDto("member", "a@example.com", password))).isNotEmpty();
                assertThat(validator.validate(new LoginReqDto("a@example.com", password))).isNotEmpty();
            }
            for (String password : new String[]{"a".repeat(72), "가".repeat(24), "password123"}) {
                assertThat(validator.validate(new MemberCreateReqDto("member", "a@example.com", password))).isEmpty();
                assertThat(validator.validate(new LoginReqDto("a@example.com", password))).isEmpty();
            }
        }
    }

    @Test void namesAndEmailsCannotExceedDatabaseColumnLength() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new MemberCreateReqDto("x".repeat(256), "a@example.com", "password123"))).isNotEmpty();
            String email = "a@" + "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(63) + ".com";
            assertThat(validator.validate(new MemberCreateReqDto("member", email, "password123"))).isNotEmpty();
            assertThat(validator.validate(new LoginReqDto(email, "password123"))).isNotEmpty();
        }
    }
}
