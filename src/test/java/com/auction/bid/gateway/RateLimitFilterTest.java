package com.auction.bid.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

    private static int chamar(RateLimitFilter filter, String userId) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/bids");
        req.setRemoteAddr("10.0.0.1");
        if (userId != null) {
            req.setAttribute(JwtAuthFilter.USER_ATTRIBUTE, userId);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    void acimaDoLimiteRetorna429() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(3);

        assertThat(chamar(filter, "u1")).isEqualTo(200);
        assertThat(chamar(filter, "u1")).isEqualTo(200);
        assertThat(chamar(filter, "u1")).isEqualTo(200);
        assertThat(chamar(filter, "u1")).isEqualTo(429);
    }

    @Test
    void usuariosTemBaldesIndependentes() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(2);

        chamar(filter, "u1");
        chamar(filter, "u1");
        assertThat(chamar(filter, "u1")).isEqualTo(429);
        assertThat(chamar(filter, "u2")).isEqualTo(200);
    }

    @Test
    void semUserIdUsaIp() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1);

        assertThat(chamar(filter, null)).isEqualTo(200);
        assertThat(chamar(filter, null)).isEqualTo(429);
    }

    @Test
    void reposicaoAposAJanelaLiberaNovamente() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(2);
        chamar(filter, "u1");
        chamar(filter, "u1");
        assertThat(chamar(filter, "u1")).isEqualTo(429);

        Thread.sleep(1_100);

        assertThat(chamar(filter, "u1")).isEqualTo(200);
    }

    @Test
    void limiteInvalidoFalhaNaConstrucao() {
        assertThatThrownBy(() -> new RateLimitFilter(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
