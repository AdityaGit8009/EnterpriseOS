package com.enterpriseos;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Server-trusted daily deduplicated invoice reminders; delivery itself uses the durable mail queue. */
@Component
@ConditionalOnProperty(name="app.business-reminders-enabled",havingValue="true",matchIfMissing=true)
public class BusinessReminderJob {
    private final JdbcTemplate db;private final BusinessController business;
    public BusinessReminderJob(JdbcTemplate db,BusinessController business){this.db=db;this.business=business;}
    @Scheduled(initialDelayString="${APP_BUSINESS_REMINDERS_INITIAL_MS:60000}",fixedDelayString="${APP_BUSINESS_REMINDERS_POLL_MS:3600000}")
    public void queueDue(){
        List<Map<String,Object>> actors=db.queryForList("SELECT m.tenant_id,m.user_id,m.role,u.name,u.email FROM memberships m JOIN users u ON u.id=m.user_id JOIN tenants t ON t.id=m.tenant_id WHERE m.role IN ('ADMIN','FINANCE') AND m.status='ACTIVE' AND u.status='ACTIVE' AND t.status='ACTIVE' ORDER BY m.tenant_id,CASE WHEN m.role='ADMIN' THEN 0 ELSE 1 END,m.user_id");
        Set<String> handled=new HashSet<>();for(Map<String,Object> actor:actors){String tenant=actor.get("tenant_id").toString();if(!handled.add(tenant))continue;Principal principal=new Principal(actor.get("user_id").toString(),tenant,actor.get("name").toString(),actor.get("email").toString(),actor.get("role").toString());try{business.invoiceReminders(principal);}catch(ApiException expected){/* Suspended entitlement or role changes are rechecked inside the tenant transaction. */}}
    }
}
