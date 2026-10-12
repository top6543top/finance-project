package com.trading.account.domain.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.account.common.exception.CustomException;
import com.trading.account.common.exception.ErrorCode;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Function;

@Service
public class IdempotencyService {
    private final IdempotencyKeyRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;
    private final RetryTemplate retry = RetryTemplate.builder().maxAttempts(5)
            .exponentialBackoff(50, 2, 400, true).retryOn(ConcurrencyFailureException.class).build();

    public IdempotencyService(IdempotencyKeyRepository repository, ObjectMapper objectMapper,
                              PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public <T> T execute(Long actorId, String key, String operation, Object canonicalRequest,
                         Class<T> responseType, Function<IdempotencyKey, T> action) {
        if (actorId == null || actorId <= 0 || key == null || !key.matches("[A-Za-z0-9._:-]{1,100}")) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
        }
        String fingerprint = fingerprint(canonicalRequest);
        try {
            // Retry surrounds commit too: no attempt can reuse a rollback-only transaction.
            return retry.execute(context -> executeAttempt(actorId, key, operation, fingerprint, responseType, action));
        } catch (ConcurrencyFailureException ex) {
            throw new CustomException(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
        }
    }

    private <T> T executeAttempt(Long actorId, String key, String operation, String fingerprint,
                                 Class<T> type, Function<IdempotencyKey, T> action) {
        try {
            return transaction.execute(status -> {
                // Legacy caches lack reliable actor/request attribution. Never execute their unknown request twice.
                if (repository.existsByActorIdAndLegacyKey(0L, key)) {
                    throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
                }
                var existing = repository.findByActorIdAndKey(actorId, key);
                if (existing.isPresent()) return replay(existing.get(), operation, fingerprint, type);

                // MySQL's named unique constraint serializes simultaneous claims for the same actor/key.
                IdempotencyKey claim = repository.saveAndFlush(new IdempotencyKey(actorId, key, operation, fingerprint));
                T result = action.apply(claim);
                claim.complete(serialize(result));
                repository.flush();
                return result;
            });
        } catch (DataIntegrityViolationException ex) {
            if (!isClaimConflict(ex)) throw ex;
            // A uniqueness error poisons its transaction: read only after rollback, using a fresh snapshot.
            return transaction.execute(status -> replay(repository.findByActorIdAndKey(actorId, key)
                    .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR)), operation, fingerprint, type));
        }
    }

    static boolean isClaimConflict(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && violation.getErrorCode() == 1062 && "23000".equals(violation.getSQLState())) {
                String constraint = violation.getConstraintName();
                return constraint != null && (constraint.equals("uk_idempotency_actor_key")
                        || constraint.endsWith(".uk_idempotency_actor_key"));
            }
        }
        return false;
    }

    private <T> T replay(IdempotencyKey claim, String operation, String fingerprint, Class<T> type) {
        if (!claim.getOperation().equals(operation) || !claim.getRequestFingerprint().equals(fingerprint)) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        if (claim.getStatus() != IdempotencyStatus.COMPLETED) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS);
        }
        try {
            return objectMapper.readValue(claim.getResponseBody(), type);
        } catch (JsonProcessingException ex) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private String fingerprint(Object request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(serialize(request).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
