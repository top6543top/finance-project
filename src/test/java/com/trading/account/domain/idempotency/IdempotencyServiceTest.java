package com.trading.account.domain.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.account.common.exception.CustomException;
import com.trading.account.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private IdempotencyKeyRepository repository;

    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        idempotencyService = new IdempotencyService(repository, new ObjectMapper());
    }

    private record Sample(String value) {
    }

    @Test
    void 새로운_키는_action을_한_번_실행하고_결과를_저장한다() {
        when(repository.findByKey("key-1")).thenReturn(Optional.of(new IdempotencyKey("key-1")));
        AtomicInteger callCount = new AtomicInteger();

        Sample result = idempotencyService.execute("key-1", Sample.class, () -> {
            callCount.incrementAndGet();
            return new Sample("hello");
        });

        assertThat(result).isEqualTo(new Sample("hello"));
        assertThat(callCount.get()).isEqualTo(1);
        verify(repository, times(2)).save(any(IdempotencyKey.class)); // insert(IN_PROGRESS) + complete(COMPLETED)
    }

    @Test
    void 완료된_키로_재요청하면_action을_실행하지_않고_저장된_응답을_반환한다() {
        when(repository.save(any(IdempotencyKey.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        IdempotencyKey completed = new IdempotencyKey("key-2");
        completed.complete("{\"value\":\"cached\"}");
        when(repository.findByKey("key-2")).thenReturn(Optional.of(completed));
        AtomicInteger callCount = new AtomicInteger();

        Sample result = idempotencyService.execute("key-2", Sample.class, () -> {
            callCount.incrementAndGet();
            return new Sample("new");
        });

        assertThat(result).isEqualTo(new Sample("cached"));
        assertThat(callCount.get()).isZero();
    }

    @Test
    void 처리중인_키로_재요청하면_409로_거절한다() {
        when(repository.save(any(IdempotencyKey.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(repository.findByKey("key-3")).thenReturn(Optional.of(new IdempotencyKey("key-3")));

        CustomException e = catchThrowableOfType(
                () -> idempotencyService.execute("key-3", Sample.class, () -> new Sample("x")),
                CustomException.class);

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS);
    }

    @Test
    void action이_실패하면_키를_지워서_재시도를_허용한다() {
        IdempotencyKey inProgress = new IdempotencyKey("key-4");
        when(repository.findByKey("key-4")).thenReturn(Optional.of(inProgress));

        CustomException e = catchThrowableOfType(
                () -> idempotencyService.execute("key-4", Sample.class, () -> {
                    throw new CustomException(ErrorCode.INSUFFICIENT_BALANCE);
                }),
                CustomException.class);

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);
        verify(repository, times(1)).delete(inProgress);
    }
}
