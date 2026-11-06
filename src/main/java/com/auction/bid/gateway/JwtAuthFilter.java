package com.auction.bid.gateway;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SIMULAÇÃO de autenticação (Princípio VI): valida JWT HS256 com segredo local.
 * user_id = claim sub, exposto como atributo de requisição "userId". Nunca lê
 * userId/X-User-Id vindos do cliente.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String USER_ATTRIBUTE = "userId";
    static final String WS_PATH = "/ws";

    private final SecretKey key;

    public JwtAuthFilter(String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        String subject;
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            subject = claims.getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (subject == null || subject.isBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        request.setAttribute(USER_ATTRIBUTE, subject);
        chain.doFilter(request, response);
    }

    private static String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length()).trim();
            return token.isEmpty() ? null : token;
        }
        // browsers não enviam Authorization no handshake WebSocket: só em /ws
        if (isWsPath(request.getRequestURI())) {
            String param = request.getParameter("access_token");
            return param == null || param.isBlank() ? null : param;
        }
        return null;
    }

    static boolean isWsPath(String uri) {
        return WS_PATH.equals(uri) || uri.startsWith(WS_PATH + "/");
    }
}
