package com.auction.bid.gateway;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate limit local em memória (Bucket4j), por user_id ou, na ausência dele, por IP.
 * Deve rodar após o JwtAuthFilter. Excesso → 429.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final int requestsPerSecond;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(int requestsPerSecond) {
        if (requestsPerSecond <= 0) {
            throw new IllegalArgumentException("requestsPerSecond deve ser > 0");
        }
        this.requestsPerSecond = requestsPerSecond;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Object userId = request.getAttribute(JwtAuthFilter.USER_ATTRIBUTE);
        String chave = userId != null ? "user:" + userId : "ip:" + request.getRemoteAddr();
        Bucket bucket = buckets.computeIfAbsent(chave, k -> novoBalde());
        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.sendError(429);
        }
    }

    private Bucket novoBalde() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(requestsPerSecond)
                        .refillGreedy(requestsPerSecond, Duration.ofSeconds(1))
                        .build())
                .build();
    }
}
