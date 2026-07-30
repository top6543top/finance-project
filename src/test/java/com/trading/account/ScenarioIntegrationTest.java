package com.trading.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.account.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// IS-14: 개별 도메인 단위 테스트, 동시성 테스트와 별개로 "실제 API를 순서대로 호출했을 때"
// 회원가입 -> 계좌개설 -> 입출금 -> 이체 -> 이력조회가 하나의 흐름으로 맞물려 정확히
// 동작하는지 검증한다. Controller -> Service -> Repository -> DB 전 레이어를 실제로 태우기
// 위해 MockMvc(전체 스프링 컨텍스트) + Testcontainers(MySQL)를 사용한다.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ScenarioIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void fullFlow_signupToTransferToHistory_succeeds() throws Exception {
        Long memberId = createMember("김유현", "scenario-owner@example.com");
        String accountA = createAccount(memberId);
        String accountB = createAccount(memberId);

        deposit(accountA, "10000").andExpect(jsonPath("$.data.balance").value(10000));
        withdraw(accountA, "2000").andExpect(jsonPath("$.data.balance").value(8000));
        transfer(accountA, accountB, "3000")
                .andExpect(jsonPath("$.data.fromAccountNumber").value(accountA))
                .andExpect(jsonPath("$.data.toAccountNumber").value(accountB));

        // 잔고: A = 10000 - 2000 - 3000 = 5000, B = 3000
        getBalance(accountA).andExpect(jsonPath("$.data.balance").value(5000));
        getBalance(accountB).andExpect(jsonPath("$.data.balance").value(3000));

        // 이력: A는 입금/출금/이체출금 3건, B는 이체입금 1건
        getHistory(accountA).andExpect(jsonPath("$.data.content.length()").value(3));
        getHistory(accountB).andExpect(jsonPath("$.data.content.length()").value(1));
    }

    @Test
    void withdraw_exceedsBalance_returnsInsufficientBalance() throws Exception {
        Long memberId = createMember("김잔고", "insufficient@example.com");
        String account = createAccount(memberId);
        deposit(account, "1000");

        withdraw(account, "5000")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.INSUFFICIENT_BALANCE.getCode()));
    }

    @Test
    void transfer_toNonExistentAccount_returnsAccountNotFound() throws Exception {
        Long memberId = createMember("김이체", "no-target@example.com");
        String account = createAccount(memberId);
        deposit(account, "1000");

        transfer(account, "000-000-0000", "500")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCOUNT_NOT_FOUND.getCode()));
    }

    @Test
    void transfer_sameFromAndToAccount_returnsSelfTransferNotAllowed() throws Exception {
        Long memberId = createMember("김자기", "self-transfer@example.com");
        String account = createAccount(memberId);
        deposit(account, "1000");

        transfer(account, account, "500")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.SELF_TRANSFER_NOT_ALLOWED.getCode()));
    }

    private Long createMember(String name, String email) throws Exception {
        String body = objectMapper.writeValueAsString(new MemberCreateReq(name, email));
        String response = mockMvc.perform(post("/api/members")
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }

    private String createAccount(Long memberId) throws Exception {
        String body = objectMapper.writeValueAsString(new AccountCreateReq(memberId));
        String response = mockMvc.perform(post("/api/accounts")
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("accountNumber").asText();
    }

    private org.springframework.test.web.servlet.ResultActions deposit(String accountNumber, String amount) throws Exception {
        return mockMvc.perform(post("/api/accounts/{accountNumber}/deposit", accountNumber)
                        .contentType("application/json").content("{\"amount\":" + amount + "}"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions withdraw(String accountNumber, String amount) throws Exception {
        return mockMvc.perform(post("/api/accounts/{accountNumber}/withdraw", accountNumber)
                .contentType("application/json").content("{\"amount\":" + amount + "}"));
    }

    private org.springframework.test.web.servlet.ResultActions transfer(String from, String to, String amount) throws Exception {
        String body = "{\"fromAccountNumber\":\"" + from + "\",\"toAccountNumber\":\"" + to + "\",\"amount\":" + amount + "}";
        return mockMvc.perform(post("/api/transfer").contentType("application/json").content(body));
    }

    private org.springframework.test.web.servlet.ResultActions getBalance(String accountNumber) throws Exception {
        return mockMvc.perform(get("/api/accounts/{accountNumber}", accountNumber))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions getHistory(String accountNumber) throws Exception {
        return mockMvc.perform(get("/api/accounts/{accountNumber}/transactions", accountNumber))
                .andExpect(status().isOk());
    }

    private record MemberCreateReq(String name, String email) {
    }

    private record AccountCreateReq(Long memberId) {
    }
}
