package com.enterpriseos;

import java.util.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@RestController
@RequestMapping("/api/v1/org")
public class CustomAccessController {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AuditService audit;
    private final OrganizationController organization;
    private final ObjectProvider<RequestMappingHandlerMapping> mappings;

    public CustomAccessController(JdbcTemplate db, ObjectMapper json, AuditService audit,
            OrganizationController organization, ObjectProvider<RequestMappingHandlerMapping> mappings) {
        this.db = db;
        this.json = json;
        this.audit = audit;
        this.organization = organization;
        this.mappings = mappings;
    }

    @GetMapping("/permission-catalog")
    public List<Map<String, Object>> catalog(@RequestAttribute Principal principal) {
        Inputs.requireRole(principal, "ADMIN");
        List<Map<String, Object>> result = new ArrayList<>();
        for (var info : mappings.getObject().getHandlerMethods().keySet())
            for (String path : info.getPatternValues())
                if (path.startsWith("/api/v1/org/"))
                    for (var method : info.getMethodsCondition().getMethods())
                        result.add(Map.of("id", method.name() + " " + path, "method", method.name(), "path", path));
        result.sort(Comparator.comparing(row -> row.get("id").toString()));
        return result;
    }

    @GetMapping("/custom-roles")
    public List<Map<String, Object>> roles(@RequestAttribute Principal principal) {
        Inputs.requireRole(principal, "ADMIN");
        return db.queryForList(
                "SELECT id,name,base_role AS baseRole,permissions,version FROM custom_roles WHERE tenant_id=? ORDER BY name",
                principal.tenantId()).stream().map(this::view).toList();
    }

    @GetMapping("/my-permissions")
    public Map<String, Object> mine(@RequestAttribute Principal principal) {
        var rows = db.queryForList(
                "SELECT r.id AS customRoleId,r.name,r.base_role AS baseRole,r.permissions FROM memberships m JOIN custom_roles r ON r.id=m.custom_role_id AND r.tenant_id=m.tenant_id WHERE m.tenant_id=? AND m.user_id=?",
                principal.tenantId(), principal.userId());
        return rows.isEmpty() ? Map.of("customRoleId", "", "name", principal.role(), "baseRole", principal.role(),
                "permissions", List.of("PREDEFINED_ROLE")) : view(rows.getFirst());
    }

    @PostMapping("/custom-roles")
    @Transactional
    public Map<String, Object> create(@RequestAttribute Principal principal, @RequestBody Map<String, Object> body) {
        return save(principal, null, body);
    }

    @PutMapping("/custom-roles/{id}")
    @Transactional
    public Map<String, Object> update(@RequestAttribute Principal principal, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return save(principal, id, body);
    }

    private Map<String, Object> save(Principal p, String id, Map<String, Object> body) {
        lockAdministrator(p);
        String name = Inputs.text(body, "name", 100), base = Inputs.text(body, "baseRole", 24);
        if (!Inputs.ROLES.contains(base) || base.equals("ADMIN"))
            throw new ApiException(400,
                    "Custom roles must be capped by a non-administrator predefined role. Keep full administrators separate.");
        if (!(body.get("permissions") instanceof List<?> values) || values.size() > 250)
            throw new ApiException(400, "Select at most 250 action permissions.");
        Set<String> available = new HashSet<>();
        for (var permission : catalog(p))
            available.add(permission.get("id").toString());
        List<String> permissions = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof String permission) || !available.contains(permission))
                throw new ApiException(400, "Select action permissions from the server catalog.");
            permissions.add(permission);
        }
        permissions = permissions.stream().distinct().toList();
        String encoded;
        try {
            encoded = json.writeValueAsString(permissions);
        } catch (Exception ex) {
            throw new ApiException(400, "Invalid permissions.");
        }
        long version = 1;
        if (id == null) {
            id = UUID.randomUUID().toString();
            db.update("INSERT INTO custom_roles(id,tenant_id,name,base_role,permissions) VALUES (?,?,?,?,?)", id,
                    p.tenantId(), name, base, encoded);
        } else {
            var rows = db.queryForList("SELECT version FROM custom_roles WHERE tenant_id=? AND id=? FOR UPDATE",
                    p.tenantId(), id);
            if (rows.isEmpty())
                throw new ApiException(404, "Custom role not found.");
            long expected = ((Number) rows.getFirst().get("version")).longValue();
            long supplied;
            try {
                supplied = new java.math.BigDecimal(String.valueOf(body.get("version"))).longValueExact();
            } catch (Exception ex) {
                throw new ApiException(400, "Current role version is required.");
            }
            if (expected != supplied)
                throw new ApiException(409, "Custom role changed. Reload before saving.");
            version = expected + 1;
            db.update("UPDATE custom_roles SET name=?,base_role=?,permissions=?,version=? WHERE tenant_id=? AND id=?",
                    name, base, encoded, version, p.tenantId(), id);
            db.update("UPDATE memberships SET role=? WHERE tenant_id=? AND custom_role_id=?", base, p.tenantId(), id);
        }
        audit.log(p, "CUSTOM_ROLE_SAVED", "customRole", id);
        return Map.of("id", id, "name", name, "baseRole", base, "permissions", permissions, "version", version);
    }

    @PutMapping("/users/{id}/custom-role")
    @Transactional
    public Map<String, Object> assign(@RequestAttribute Principal principal, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        lockAdministrator(principal);
        String roleId = Inputs.optional(body, "customRoleId", 36, "");
        if (db.queryForObject("SELECT COUNT(*) FROM memberships WHERE tenant_id=? AND user_id=?", Long.class,
                principal.tenantId(), id) == 0)
            throw new ApiException(404, "Member not found.");
        if (!roleId.isBlank()) {
            var roles = db.queryForList("SELECT base_role FROM custom_roles WHERE tenant_id=? AND id=?",
                    principal.tenantId(), roleId);
            if (roles.isEmpty())
                throw new ApiException(404, "Custom role not found.");
            organization.userRole(principal, id, Map.of("role", roles.getFirst().get("base_role")));
        }
        db.update("UPDATE memberships SET custom_role_id=? WHERE tenant_id=? AND user_id=?",
                roleId.isBlank() ? null : roleId, principal.tenantId(), id);
        if (!roleId.isBlank())
            db.update(
                    "UPDATE api_credentials SET revoked_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND created_by=? AND revoked_at IS NULL",
                    principal.tenantId(), id);
        audit.log(principal, "CUSTOM_ROLE_ASSIGNED", "user", id);
        return Map.of("id", id, "customRoleId", roleId);
    }

    private Map<String, Object> view(Map<String, Object> row) {
        try {
            row.put("permissions", json.readValue(row.get("permissions").toString(), new TypeReference<List<String>>() {
            }));
            return row;
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid stored role policy", ex);
        }
    }

    private void lockAdministrator(Principal p) {
        Inputs.requireRole(p, "ADMIN");
        db.queryForList("SELECT id FROM tenants WHERE id=? FOR UPDATE", p.tenantId());
        if (db.queryForObject(
                "SELECT COUNT(*) FROM memberships m JOIN tenants t ON t.id=m.tenant_id JOIN users u ON u.id=m.user_id WHERE m.tenant_id=? AND m.user_id=? AND m.role='ADMIN' AND m.status='ACTIVE' AND t.status='ACTIVE' AND u.status='ACTIVE'",
                Long.class, p.tenantId(), p.userId()) != 1)
            throw new ApiException(403, "Administrator authority changed. Sign in again.");
    }
}
