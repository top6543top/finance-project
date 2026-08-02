package com.trading.account.common.logging;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void 헤더가_없으면_새로_생성해서_MDC와_응답헤더에_모두_싣는다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] mdcValueDuringChain = new String[1];
        FilterChain chain = (req, res) -> mdcValueDuringChain[0] = MDC.get("requestId");

        filter.doFilter(request, response, chain);

        assertThat(mdcValueDuringChain[0]).isNotBlank();
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(mdcValueDuringChain[0]);
        assertThat(MDC.get("requestId")).isNull(); // 필터 체인이 끝나면 정리돼야 다음 요청에 안 새어나감
    }

    @Test
    void 헤더가_있으면_그대로_재사용한다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "client-provided-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("client-provided-id");
    }
}
