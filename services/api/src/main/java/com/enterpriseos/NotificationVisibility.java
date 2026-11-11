package com.enterpriseos;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Current object authorization for notifications containing task-comment
 * content. No audit/service dependency cycle.
 */
@Service
public class NotificationVisibility {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final CustomAccessPolicy custom;

    public NotificationVisibility(JdbcTemplate db, ObjectMapper json, CustomAccessPolicy custom) {
        this.db = db;
        this.json = json;
        this.custom = custom;
    }

    public boolean allowed(Principal p, String eventKey) {
        var rows = db.queryForList(
                "SELECT project_id FROM notification_mentions WHERE tenant_id=? AND CONCAT(event_key,':',?)=?",
                p.tenantId(), p.userId(), eventKey);
        return rows.isEmpty() || rows.getFirst().get("project_id") == null
                || project(p, rows.getFirst().get("project_id").toString());
    }

    public boolean project(Principal p, String projectId) {
        if (!custom.allowed(p, "GET", "/api/v1/org/records/tasks"))
            return false;
        var projects = db.queryForList(
                "SELECT payload FROM business_records WHERE tenant_id=? AND kind='projects' AND id=?", p.tenantId(),
                projectId);
        if (projects.isEmpty())
            return false;
        try {
            Map<?, ?> row = json.readValue(projects.getFirst().get("payload").toString(), Map.class);
            return Set.of("ADMIN", "MANAGER", "AUDITOR").contains(p.role()) || p.userId().equals(row.get("ownerId"))
                    || (row.get("memberIds") instanceof List<?> ids && ids.contains(p.userId()));
        } catch (Exception ex) {
            return false;
        }
    }
}
