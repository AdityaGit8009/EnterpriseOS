package com.enterpriseos;

import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Uses current server-verified HR/admin memberships; each called transition opens its own transaction. */
@Component
@ConditionalOnProperty(name="app.workforce-jobs-enabled",havingValue="true",matchIfMissing=true)
public class WorkforceExtensionJob {
    private final JdbcTemplate db;private final WorkforceExtensionController workforce;
    public WorkforceExtensionJob(JdbcTemplate db,WorkforceExtensionController workforce){this.db=db;this.workforce=workforce;}
    @Scheduled(initialDelayString="${APP_WORKFORCE_JOBS_INITIAL_MS:60000}",fixedDelayString="${APP_WORKFORCE_JOBS_POLL_MS:3600000}")
    public void run(){
        List<Map<String,Object>> rows=db.queryForList("SELECT m.tenant_id,m.user_id,m.role,u.name,u.email,t.timezone FROM memberships m JOIN users u ON u.id=m.user_id JOIN tenants t ON t.id=m.tenant_id WHERE m.role IN ('ADMIN','HR') AND m.status='ACTIVE' AND u.status='ACTIVE' AND t.status='ACTIVE' ORDER BY m.tenant_id,CASE WHEN m.role='ADMIN' THEN 0 ELSE 1 END,m.user_id");
        Map<String,List<Principal>> actors=new LinkedHashMap<>();Map<String,String> zones=new HashMap<>();for(Map<String,Object> r:rows){String tenant=r.get("tenant_id").toString();actors.computeIfAbsent(tenant,k->new ArrayList<>()).add(new Principal(r.get("user_id").toString(),tenant,r.get("name").toString(),r.get("email").toString(),r.get("role").toString()));zones.put(tenant,r.get("timezone").toString());}
        for(var entry:actors.entrySet()){String tenant=entry.getKey();List<Principal> users=entry.getValue();LocalDate today=LocalDate.now(java.time.ZoneId.of(zones.get(tenant)));for(Map<String,Object> request:db.queryForList("SELECT id,user_id FROM workforce_requests WHERE tenant_id=? AND kind='exits' AND status='APPROVED' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.exitDate'))<=?",tenant,today.toString())){Optional<Principal> actor=users.stream().filter(p->!p.userId().equals(request.get("user_id"))).findFirst();if(actor.isPresent())try{workforce.completeExit(actor.get(),request.get("id").toString());}catch(ApiException expected){/* Last-admin, entitlement and access changes remain visible as an uncompleted approved request. */}}
            users.stream().filter(p->p.role().equals("ADMIN")).findFirst().ifPresent(p->{try{workforce.runDue(p);}catch(ApiException expected){/* A suspended entitlement never bypasses the application guard. */}});
        }
    }
}
