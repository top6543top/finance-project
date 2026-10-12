package com.trading.account.domain.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {
    Optional<IdempotencyKey> findByActorIdAndKey(Long actorId, String key);

    boolean existsByActorIdAndLegacyKey(Long actorId, String legacyKey);
}
