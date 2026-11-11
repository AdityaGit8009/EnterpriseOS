package com.enterpriseos;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.time.ZoneId;

final class Inputs {
    static final Set<String> ROLES = Set.of("ADMIN", "HR", "MANAGER", "MEMBER", "AUDITOR", "SALES", "PROCUREMENT", "FINANCE", "PAYROLL");
    private Inputs() { }
    static String text(Map<String, ?> body, String key, int max) {
        Object raw = body.get(key);
        if (!(raw instanceof String value) || value.trim().isEmpty() || value.trim().length() > max)
            throw new ApiException(400, key + " is required and must be at most " + max + " characters.");
        return value.trim();
    }
    static String optional(Map<String, ?> body, String key, int max, String fallback) {
        if (body.get(key) == null) return fallback;
        if (!(body.get(key) instanceof String value) || value.length() > max)
            throw new ApiException(400, key + " is invalid.");
        return value.trim();
    }
    static String email(Map<String, ?> body, String key) {
        String email = text(body, key, 254).toLowerCase(Locale.ROOT);
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new ApiException(400, "Enter a valid email address.");
        return email;
    }
    static String password(Map<String, ?> body) {
        Object raw = body.get("password");
        if (!(raw instanceof String password) || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ApiException(400, "Password must contain at least 12 characters and at most 72 UTF-8 bytes.");
        return password;
    }
    static String role(Map<String, ?> body) {
        String role = text(body, "role", 24);
        if (!ROLES.contains(role)) throw new ApiException(400, "Choose a predefined organization role.");
        return role;
    }
    static String timezone(Map<String, ?> body) {
        String zone = optional(body, "timezone", 80, "UTC");
        try { ZoneId.of(zone); } catch (Exception ex) { throw new ApiException(400, "Choose a valid IANA timezone."); }
        return zone;
    }
    static void requireRole(Principal actor, String... allowed) {
        for (String role : allowed) if (role.equals(actor.role())) return;
        throw new ApiException(403, "Your role cannot perform this action.");
    }
}
