package com.auction.bid.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class JwtAuthFilterTest {

    private static final String SECRET = "segredo-de-teste-com-pelo-menos-32-bytes!!";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private final JwtAuthFilter filter = new JwtAuthFilter(SECRET);

    private static String token(String sub, Date expiraEm, SecretKey chave) {
        return Jwts.builder().subject(sub).expiration(expiraEm).signWith(chave).compact();
    }

    private static Date daqui(long segundos) {
        return new Date(System.currentTimeMillis() + segundos * 1000);
    }

    private static MockHttpServletRequest bids(String authorization) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/bids");
        if (authorization != null) {
            req.addHeader("Authorization", authorization);
        }
        return req;
    }

    @Test
    void semTokenRetorna401SemInvocarACadeia() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(bids(null), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void tokenMalformadoRetorna401() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(bids("Bearer isto-nao-e-jwt"), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void assinaturaInvalidaRetorna401() throws Exception {
        SecretKey outra = Keys.hmacShaKeyFor("outra-chave-bem-diferente-com-32-bytes!!".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(bids("Bearer " + token("u1", daqui(60), outra)), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void tokenExpiradoRetorna401() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(bids("Bearer " + token("u1", daqui(-60), KEY)), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void tokenSemSubRetorna401() throws Exception {
        String semSub = Jwts.builder().expiration(daqui(60)).signWith(KEY).compact();
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(bids("Bearer " + semSub), res, new MockFilterChain());

        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void tokenValidoPassaComUserIdDoSubIgnorandoHeaderDoCliente() throws Exception {
        MockHttpServletRequest req = bids("Bearer " + token("u1", daqui(60), KEY));
        req.addHeader("X-User-Id", "hacker");
        req.setAttribute("userId", "tambem-falso");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(req.getAttribute("userId")).isEqualTo("u1");
    }

    @Test
    void wsAceitaTokenNaQueryString() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ws");
        req.setParameter("access_token", token("u2", daqui(60), KEY));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(req.getAttribute("userId")).isEqualTo("u2");
    }

    @Test
    void queryStringNaoValeForaDoWebSocket() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/bids");
        req.setParameter("access_token", token("u2", daqui(60), KEY));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, new MockFilterChain());

        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void bearerVazioRetorna401() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(bids("Bearer    "), res, new MockFilterChain());

        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void segredoFracoFalhaNaConstrucao() {
        assertThatThrownBy(() -> new JwtAuthFilter("curto"))
                .isInstanceOf(RuntimeException.class);
    }
}
