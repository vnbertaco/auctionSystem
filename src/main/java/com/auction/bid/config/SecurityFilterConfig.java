package com.auction.bid.config;

import com.auction.bid.gateway.JwtAuthFilter;
import com.auction.bid.gateway.RateLimitFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registra os filtros de entrada (simulações, Princípio VI) em /bids e /ws.
 * Ordem: JWT primeiro, rate limit depois (requisição sem token não consome cota).
 * Desligável por auction.gateway.enabled=false (usado nos testes de integração de US1).
 */
@Configuration
@ConditionalOnProperty(name = "auction.gateway.enabled", havingValue = "true", matchIfMissing = true)
public class SecurityFilterConfig {

    private static final String[] PATTERNS = {"/bids", "/ws", "/ws/*"};

    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilter(
            @Value("${jwt.secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET não configurado (jwt.secret)");
        }
        FilterRegistrationBean<JwtAuthFilter> registro = new FilterRegistrationBean<>(new JwtAuthFilter(secret));
        registro.addUrlPatterns(PATTERNS);
        registro.setOrder(1);
        return registro;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
            @Value("${ratelimit.requests-per-second:20}") int requestsPerSecond) {
        FilterRegistrationBean<RateLimitFilter> registro =
                new FilterRegistrationBean<>(new RateLimitFilter(requestsPerSecond));
        registro.addUrlPatterns(PATTERNS);
        registro.setOrder(2);
        return registro;
    }
}
