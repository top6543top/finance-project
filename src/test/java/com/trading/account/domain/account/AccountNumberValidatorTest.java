package com.trading.account.domain.account;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountNumberValidatorTest {

    @Test
    void 체크디지트를_붙이면_검증을_통과한다() {
        String withCheckDigit = AccountNumberValidator.appendCheckDigit("1234567890");

        assertThat(AccountNumberValidator.isValid(withCheckDigit)).isTrue();
    }

    @Test
    void 대시가_섞여있어도_검증한다() {
        String withCheckDigit = AccountNumberValidator.appendCheckDigit("1234567890");
        String dashed = withCheckDigit.substring(0, 3) + "-" + withCheckDigit.substring(3, 6) + "-" + withCheckDigit.substring(6);

        assertThat(AccountNumberValidator.isValid(dashed)).isTrue();
    }

    @Test
    void 한_자리라도_틀리면_실패한다() {
        String withCheckDigit = AccountNumberValidator.appendCheckDigit("1234567890");
        char[] tampered = withCheckDigit.toCharArray();
        tampered[0] = tampered[0] == '9' ? '8' : '9';

        assertThat(AccountNumberValidator.isValid(new String(tampered))).isFalse();
    }

    @Test
    void null이거나_숫자가_아니면_실패한다() {
        assertThat(AccountNumberValidator.isValid(null)).isFalse();
        assertThat(AccountNumberValidator.isValid("")).isFalse();
        assertThat(AccountNumberValidator.isValid("abc-def-ghij")).isFalse();
        assertThat(AccountNumberValidator.isValid("1")).isFalse();
    }

    @Test
    void 짧아도_Luhn_합이_우연히_맞으면_통과하던_문제를_막는다() {
        // "000"은 3자리뿐이라 실제 계좌번호(11자리) 형식이 아니지만, 전부 0이라 Luhn 합도 우연히 0으로 떨어짐
        assertThat(AccountNumberValidator.isValid("000")).isFalse();
    }

    @Test
    void 전각_숫자는_ASCII_숫자가_아니므로_실패한다() {
        // Character.isDigit()은 U+FF10(전각 0) 같은 유니코드 숫자도 true를 반환하지만,
        // 체크섬 계산은 ASCII '0'~'9' 연산이라 그대로 통과시키면 안 됨
        assertThat(AccountNumberValidator.isValid("１２３４５６７８９０３")).isFalse();
    }
}
