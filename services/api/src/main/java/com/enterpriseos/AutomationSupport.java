package com.enterpriseos;

import java.math.BigDecimal;
import java.util.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AutomationSupport {
    final JdbcTemplate db;
    final ObjectMapper json;
    final AuditService audit;
    final AutomationEvents events;
    final EntitlementService entitlements;

    public AutomationSupport(JdbcTemplate db, ObjectMapper json, AuditService audit, AutomationEvents events,
            EntitlementService entitlements) {
        this.db = db;
        this.json = json;
        this.audit = audit;
        this.events = events;
        this.entitlements = entitlements;
    }

    void org(Principal p) {
        if (p.tenantId() == null || p.role().equals("PLATFORM_ADMIN"))
            throw new ApiException(403, "Organization access is required.");
    }

    void allow(Principal p, String... roles) {
        org(p);
        Inputs.requireRole(p, roles);
    }

    void lock(Principal p, String module, boolean create) {
        org(p);
        var tenants = db.queryForList("SELECT status FROM tenants WHERE id=? FOR UPDATE", p.tenantId());
        if (tenants.isEmpty() || !"ACTIVE".equals(tenants.getFirst().get("status")))
            throw new ApiException(403, "Organization is inactive.");
        if (db.queryForObject(
                "SELECT COUNT(*) FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.tenant_id=? AND m.user_id=? AND m.role=? AND m.status='ACTIVE' AND u.status='ACTIVE'",
                Integer.class, p.tenantId(), p.userId(), p.role()) != 1)
            throw new ApiException(403, "Your membership changed. Sign in again.");
        entitlements.require(p, module, create);
    }

    void activeUser(String tenant, String user) {
        if (db.queryForObject(
                "SELECT COUNT(*) FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.tenant_id=? AND m.user_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'",
                Integer.class, tenant, user) != 1)
            throw new ApiException(400, "Choose an active user in your organization.");
    }

    String id() {
        return UUID.randomUUID().toString();
    }

    String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new ApiException(400, "Invalid JSON data.");
        }
    }

    Map<String, Object> decode(Object value) {
        try {
            return json.readValue(value.toString(), new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid stored JSON", ex);
        }
    }

    List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list))
            throw new ApiException(400, "Provide a list of steps.");
        return list.stream().map(v -> {
            if (!(v instanceof Map<?, ?> m))
                throw new ApiException(400, "Each step must be an object.");
            Map<String, Object> result = new LinkedHashMap<>();
            m.forEach((k, o) -> result.put(k.toString(), o));
            return result;
        }).toList();
    }

    BigDecimal decimal(Map<String, Object> body, String field, BigDecimal fallback) {
        try {
            BigDecimal n = body.get(field) == null ? fallback : new BigDecimal(body.get(field).toString());
            if (n == null || n.scale() > 4 || n.abs().compareTo(new BigDecimal("999999999999999")) > 0)
                throw new NumberFormatException();
            return n;
        } catch (Exception ex) {
            throw new ApiException(400, field + " must be a valid decimal.");
        }
    }

    int integer(Map<String, Object> body, String field, int fallback, int min, int max) {
        try {
            int n = body.get(field) == null ? fallback : Integer.parseInt(body.get(field).toString());
            if (n < min || n > max)
                throw new NumberFormatException();
            return n;
        } catch (Exception ex) {
            throw new ApiException(400, field + " must be between " + min + " and " + max + ".");
        }
    }

    Map<String, Object> first(List<Map<String, Object>> rows, String label) {
        if (rows.isEmpty())
            throw new ApiException(404, label + " was not found in your organization.");
        return rows.getFirst();
    }
}
