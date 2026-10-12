package com.trading.account.domain.idempotency;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLIntegrityConstraintViolationException;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyConstraintTest {
    @Test
    void onlyNamedMySqlDuplicateClaimConstraintAllowsReplay() {
        assertThat(IdempotencyService.isClaimConflict(violation(1062, "uk_idempotency_actor_key"))).isTrue();
        assertThat(IdempotencyService.isClaimConflict(violation(1062, "idempotency_key.uk_idempotency_actor_key"))).isTrue();
        assertThat(IdempotencyService.isClaimConflict(violation(1062, "some_other_unique"))).isFalse();
        assertThat(IdempotencyService.isClaimConflict(violation(1048, "uk_idempotency_actor_key"))).isFalse();
        assertThat(IdempotencyService.isClaimConflict(new DataIntegrityViolationException("not a duplicate"))).isFalse();
    }

    private DataIntegrityViolationException violation(int code, String constraint) {
        var sql = new SQLIntegrityConstraintViolationException("constraint failure", "23000", code);
        return new DataIntegrityViolationException("storage failure", new ConstraintViolationException("constraint failure", sql, constraint));
    }
}
