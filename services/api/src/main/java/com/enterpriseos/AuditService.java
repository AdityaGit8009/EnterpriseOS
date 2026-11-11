package com.enterpriseos;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    private final AutomationEvents events;
    public AuditService(JdbcTemplate jdbc, AutomationEvents events) { this.jdbc = jdbc; this.events = events; }
    public void log(Principal actor, String action, String entity, String entityId) {
        String eventId=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO audit_events(id,tenant_id,actor_id,actor_name,action,entity,entity_id,created_at) VALUES (?,?,?,?,?,?,?,UTC_TIMESTAMP(6))",
            eventId, actor.tenantId(), actor.userId(), actor.name(), action, entity, entityId);
        events.publish(actor,action,entity,entityId,eventId);
    }
}
