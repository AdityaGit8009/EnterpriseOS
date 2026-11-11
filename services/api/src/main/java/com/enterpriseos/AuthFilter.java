package com.enterpriseos;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLIC = Set.of("/api/v1/setup", "/api/v1/auth/register-company", "/api/v1/auth/verify-company", "/api/v1/auth/login", "/api/v1/auth/logout", "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password", "/api/v1/invitations/accept");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Set<String> origins;
    public AuthFilter(JdbcTemplate jdbc, ObjectMapper mapper, @Value("${app.allowed-origins}") String origins) {
        this.jdbc = jdbc; this.mapper = mapper;
        this.origins = Arrays.stream(origins.split(",")).map(String::trim).collect(Collectors.toUnmodifiableSet());
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "same-origin");
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) { chain.doFilter(request, response); return; }
        response.setHeader("Cache-Control", "no-store");
        // A signed inbound webhook has no cookie authority; its controller verifies HMAC, time and replay ID.
        if ("POST".equals(request.getMethod()) && path.matches("/api/v1/integrations/webhooks/[0-9a-fA-F-]{36}")) { chain.doFilter(request, response); return; }
        boolean mutation = !Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
        String origin = request.getHeader("Origin");
        if (mutation && (origin == null || !origins.contains(origin) || "cross-site".equals(request.getHeader("Sec-Fetch-Site")))) {
            deny(response, 403, "Request origin is not allowed.", "ORIGIN_DENIED"); return;
        }
        if ("OPTIONS".equals(request.getMethod())) { deny(response, 403, "Cross-origin requests are disabled. Use the same-origin API proxy.", "ORIGIN_DENIED"); return; }
        if (PUBLIC.contains(path)) { chain.doFilter(request, response); return; }
        // These read-only integration routes perform their own hashed bearer-token and scope validation.
        if ("GET".equals(request.getMethod()) && path.startsWith("/api/v1/integrations/data/")) { chain.doFilter(request, response); return; }
        String raw = sessionCookie(request);
        if (raw == null || raw.length() > 256) { deny(response, 401, "Sign in to continue.", "UNAUTHENTICATED"); return; }
        var principals = jdbc.query("""
            SELECT u.id,u.name,u.email,s.tenant_id,
              CASE WHEN s.tenant_id IS NULL THEN u.platform_role ELSE m.role END AS resolved_role
            FROM sessions s JOIN users u ON u.id=s.user_id
            LEFT JOIN tenants t ON t.id=s.tenant_id
            LEFT JOIN memberships m ON m.tenant_id=s.tenant_id AND m.user_id=u.id
            WHERE s.token_hash=? AND s.revoked_at IS NULL AND s.expires_at>UTC_TIMESTAMP(6)
              AND u.status='ACTIVE'
              AND ((s.tenant_id IS NULL AND u.platform_role='PLATFORM_ADMIN')
                OR (s.tenant_id IS NOT NULL AND t.status='ACTIVE' AND m.status='ACTIVE'))
            """, (rs, row) -> new Principal(rs.getString("id"), rs.getString("tenant_id"), rs.getString("name"), rs.getString("email"), rs.getString("resolved_role")), Tokens.hash(raw));
        if (principals.isEmpty()) { deny(response, 401, "Your session or organization access has expired. Sign in again.", "UNAUTHENTICATED"); return; }
        Principal principal = principals.getFirst();
        if ((path.startsWith("/api/v1/platform/") && !"PLATFORM_ADMIN".equals(principal.role()))
                || (path.startsWith("/api/v1/org/") && principal.tenantId() == null)) {
            deny(response, 403, "This workspace is not available to your role.", "ACCESS_DENIED"); return;
        }
        request.setAttribute("principal", principal);
        chain.doFilter(request, response);
    }
    static String sessionCookie(HttpServletRequest request) {
        if (request.getCookies() != null) for (Cookie cookie : request.getCookies()) if ("EOS_SESSION".equals(cookie.getName())) return cookie.getValue();
        return null;
    }
    private void deny(HttpServletResponse response, int status, String message, String code) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(), Map.of("message", message, "code", code));
    }
}
