package com.auction.bid.integration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Substitui o JwtAuthFilter nos testes de US1: copia o header X-User-Id para o atributo
 * "userId" (o mesmo contrato que o filtro real cumpre com o claim sub).
 */
public class TestUserFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-User-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String userId = request.getHeader(HEADER);
        if (userId != null) {
            request.setAttribute("userId", userId);
        }
        chain.doFilter(request, response);
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        public FilterRegistrationBean<TestUserFilter> testUserFilter() {
            FilterRegistrationBean<TestUserFilter> registro = new FilterRegistrationBean<>(new TestUserFilter());
            registro.addUrlPatterns("/*");
            registro.setOrder(0);
            return registro;
        }
    }
}
