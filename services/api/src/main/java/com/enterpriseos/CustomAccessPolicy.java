package com.enterpriseos;

import java.util.List;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;

@Service
public class CustomAccessPolicy {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public CustomAccessPolicy(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public boolean allowed(Principal p, String method, String actualPath) {
        String path = actualPath.startsWith("/api/") ? actualPath : "/api/v1" + actualPath;
        var rows = db.queryForList(
                "SELECT r.permissions FROM memberships m JOIN custom_roles r ON r.id=m.custom_role_id AND r.tenant_id=m.tenant_id WHERE m.tenant_id=? AND m.user_id=?",
                p.tenantId(), p.userId());
        if (rows.isEmpty())
            return true;
        try {
            List<String> permissions = json.readValue(rows.getFirst().get("permissions").toString(),
                    new TypeReference<List<String>>() {
                    });
            return permissions.stream().anyMatch(permission -> {
                int space = permission.indexOf(' ');
                return space > 0 && method.equals(permission.substring(0, space))
                        && matcher.match(permission.substring(space + 1), path);
            });
        } catch (Exception ex) {
            return false;
        }
    }
}
