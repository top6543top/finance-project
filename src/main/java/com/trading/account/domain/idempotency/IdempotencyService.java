package com.trading.account.domain.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.account.common.exception.CustomException;
import com.trading.account.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyKeyRepository repository;
    private final ObjectMapper objectMapper;

    // Idempotency-Key로 action을 딱 한 번만 실행시킨다.
    // 같은 키로 또 들어오면: 이미 끝났으면 그때 응답을 그대로 돌려주고, 아직 처리 중이면 409로 거절한다.
    public <T> T execute(String key, Class<T> responseType, Supplier<T> action) {
        try {
            repository.save(new IdempotencyKey(key));
        } catch (DataIntegrityViolationException e) {
            return replayOrReject(key, responseType);
        }

        try {
            T result = action.get();
            complete(key, result);
            return result;
        } catch (RuntimeException e) {
            // 실패한 요청은 아무 부작용도 안 남았으므로 행을 지워서 같은 키로 재시도 가능하게 둔다.
            // deleteByKey(파생 쿼리)는 SimpleJpaRepository 소속이 아니라 자체 @Transactional이
            // 없어서 앰비언트 트랜잭션 밖(컨트롤러 호출)에서 단독 실행하면 예외가 난다 —
            // save/delete(entity)처럼 SimpleJpaRepository가 직접 제공하는 메서드만 단독 호출이 안전하다.
            repository.findByKey(key).ifPresent(repository::delete);
            throw e;
        }
    }

    private <T> T replayOrReject(String key, Class<T> responseType) {
        IdempotencyKey existing = repository.findByKey(key)
                .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR));
        if (existing.getStatus() != IdempotencyStatus.COMPLETED) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS);
        }
        return deserialize(existing.getResponseBody(), responseType);
    }

    private void complete(String key, Object result) {
        IdempotencyKey saved = repository.findByKey(key)
                .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR));
        saved.complete(serialize(result));
        repository.save(saved);
    }

    private String serialize(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
