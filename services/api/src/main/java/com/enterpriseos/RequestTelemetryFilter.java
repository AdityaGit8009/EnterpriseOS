package com.enterpriseos;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ReadListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Bounded, process-local telemetry; logs contain route templates, never
 * bodies/query tokens.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RequestTelemetryFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestTelemetryFilter.class);
    private final LongAdder requests = new LongAdder(), errors = new LongAdder(), elapsed = new LongAdder();
    private final ConcurrentHashMap<String, Window> limits = new ConcurrentHashMap<>();

    private record Window(long start, int count) {
    }

    public Map<String, Object> snapshot() {
        return Map.of("requests", requests.sum(), "serverErrors", errors.sum(), "totalDurationMillis", elapsed.sum(),
                "scope", "current process since startup");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String correlation = UUID.randomUUID().toString();
        response.setHeader("X-Request-ID", correlation);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        if (request.isSecure())
            response.setHeader("Strict-Transport-Security", "max-age=31536000");
        long start = System.nanoTime();
        try {
            String path = request.getRequestURI();
            if (request.getContentLengthLong() > 8L * 1024 * 1024) {
                response.setStatus(413);
                response.setContentType("application/json");
                response.getWriter()
                        .write("{\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\"Request body exceeds 8 MB.\"}");
                return;
            }
            if ("POST".equals(request.getMethod())
                    && (path.startsWith("/api/v1/auth/") || path.startsWith("/api/v1/me/mfa/")
                            || path.equals("/api/v1/setup") || path.equals("/api/v1/invitations/accept"))) {
                long now = System.currentTimeMillis();
                if (limits.size() > 4096)
                    limits.entrySet().removeIf(e -> now - e.getValue().start() > 60_000);
                String key = request.getRemoteAddr();
                if (limits.size() > 8192 && !limits.containsKey(key)) {
                    limited(response);
                    return;
                }
                Window window = limits.compute(key,
                        (k, old) -> old == null || now - old.start() > 60_000 ? new Window(now, 1)
                                : new Window(old.start(), old.count() + 1));
                if (window.count() > 60) {
                    limited(response);
                    return;
                }
            }
            chain.doFilter(new LimitedRequest(request), response);
        } finally {
            long millis = (System.nanoTime() - start) / 1_000_000;
            requests.increment();
            elapsed.add(millis);
            if (response.getStatus() >= 500)
                errors.increment();
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            LOG.info("http requestId={} method={} route={} status={} durationMs={}", correlation, request.getMethod(),
                    route == null ? "unmatched" : route, response.getStatus(), millis);
        }
    }

    private void limited(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", "60");
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"code\":\"RATE_LIMITED\",\"message\":\"Too many authentication requests. Retry in one minute.\"}");
    }

    static final class LimitedRequest extends HttpServletRequestWrapper {
        private ServletInputStream bounded;

        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (bounded != null)
                return bounded;
            ServletInputStream input = super.getInputStream();
            bounded = new ServletInputStream() {
                private long count;

                private void add(int size) throws IOException {
                    if (size > 0 && ((count += size) > 8L * 1024 * 1024))
                        throw new IOException("Request body exceeds 8 MB");
                }

                @Override
                public int read() throws IOException {
                    int value = input.read();
                    if (value >= 0)
                        add(1);
                    return value;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    if (count > 8L * 1024 * 1024)
                        throw new IOException("Request body exceeds 8 MB");
                    int value = input.read(bytes, offset, (int) Math.min(length, 8L * 1024 * 1024 - count + 1));
                    add(value);
                    return value;
                }

                @Override
                public void close() throws IOException {
                    input.close();
                }

                @Override
                public boolean isFinished() {
                    return input.isFinished();
                }

                @Override
                public boolean isReady() {
                    return input.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    input.setReadListener(listener);
                }
            };
            return bounded;
        }

        @Override
        public java.io.BufferedReader getReader() throws IOException {
            return new java.io.BufferedReader(
                    new java.io.InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
