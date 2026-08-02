package com.trading.account.domain.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trading.account.common.exception.CustomException;
import com.trading.account.common.exception.ErrorCode;
import com.trading.account.common.exception.GlobalExceptionHandler;
import com.trading.account.domain.idempotency.IdempotencyService;
import com.trading.account.domain.transaction.dto.TransactionResDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class TransactionControllerTest {

    @Mock
    private TransactionService transactionService;

    @Mock
    private IdempotencyService idempotencyService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mockMvc = standaloneSetup(new TransactionController(transactionService, idempotencyService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();

        // idempotencyService는 이 테스트에서 검증 대상이 아니므로, 컨트롤러가 넘긴 action을
        // 그대로 한 번 실행해서 감싸기 전과 동일하게 동작하도록 스텁을 깔아둔다 (IdempotencyServiceTest에서 별도 검증)
        lenient().when(idempotencyService.execute(anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(2)).get());
    }

    private static final Authentication AUTH = new UsernamePasswordAuthenticationToken(1L, null);

    @Test
    void deposit_valid_returns200() throws Exception {
        when(transactionService.deposit(anyString(), any(), any()))
                .thenReturn(new TransactionResDto("123-456-7890", BigDecimal.valueOf(1500), BigDecimal.valueOf(500),
                        TransactionType.DEPOSIT, LocalDateTime.now()));

        mockMvc.perform(post("/api/accounts/123-456-7890/deposit")
                        .principal(AUTH)
                        .header("Idempotency-Key", "test-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(1500));
    }

    @Test
    void deposit_negativeAmount_returns400() throws Exception {
        mockMvc.perform(post("/api/accounts/123-456-7890/deposit")
                        .principal(AUTH)
                        .header("Idempotency-Key", "test-key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deposit_missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/api/accounts/123-456-7890/deposit")
                        .principal(AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":500}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void withdraw_insufficientBalance_returns409() throws Exception {
        when(transactionService.withdraw(anyString(), any(), any()))
                .thenThrow(new CustomException(ErrorCode.INSUFFICIENT_BALANCE));

        mockMvc.perform(post("/api/accounts/123-456-7890/withdraw")
                        .principal(AUTH)
                        .header("Idempotency-Key", "test-key-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":999999}"))
                .andExpect(status().isConflict());
    }
}
