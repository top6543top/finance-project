package com.trading.account.common.exception;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.trading.account.domain.member.dto.MemberCreateReqDto;
import jakarta.validation.Valid;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ExceptionLoggingSafetyTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private MockMvc mvc;

    @BeforeEach void setUp() {
        logs.start();
        logger.addAppender(logs);
        mvc = standaloneSetup(new InputController()).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach void tearDown() { logger.detachAppender(logs); logs.stop(); }

    @Test void rejectedPasswordNeverAppearsInHandlerLogs() throws Exception {
        mvc.perform(post("/input").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"test\",\"email\":\"secret@example.com\",\"password\":\"probe7\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.password").exists());
        assertSafeLogs("probe7", "secret@example.com");
    }

    @Test void parsingAndTypeMismatchNeverLogRejectedInput() throws Exception {
        mvc.perform(post("/number").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"Bearer-secret-token\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/number/123456789-secret-account"))
                .andExpect(status().isBadRequest());
        assertSafeLogs("Bearer-secret-token", "123456789-secret-account");
    }

    @Test void unexpectedExceptionMessageAndCauseAreNotLogged() throws Exception {
        mvc.perform(get("/unexpected")).andExpect(status().isInternalServerError());
        assertSafeLogs("secret-password", "secret-cause");
    }

    private void assertSafeLogs(String... secrets) {
        assertThat(logs.list).isNotEmpty();
        for (ILoggingEvent event : logs.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(secrets);
            assertThat(event.getThrowableProxy()).isNull();
        }
    }

    @RestController static class InputController {
        @PostMapping("/input") void input(@Valid @RequestBody MemberCreateReqDto input) {}
        @PostMapping("/number") void number(@RequestBody NumberInput input) {}
        @GetMapping("/number/{id}") void numberPath(@PathVariable Long id) {}
        @GetMapping("/unexpected") void unexpected() {
            throw new IllegalStateException("secret-password", new RuntimeException("secret-cause"));
        }
    }
    record NumberInput(Long id) {}
}
