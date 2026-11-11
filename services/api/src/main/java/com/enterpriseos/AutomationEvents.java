package com.enterpriseos;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AutomationEvents {
    private final JdbcTemplate db;private final ObjectMapper json;private final MailService mail;private final NotificationStream stream;
    public AutomationEvents(JdbcTemplate db,ObjectMapper json,MailService mail,NotificationStream stream){this.db=db;this.json=json;this.mail=mail;this.stream=stream;}
    public void publish(Principal actor,String event,String entity,String entityId){publish(actor,event,entity,entityId,UUID.randomUUID().toString());}
    public void publish(Principal actor,String event,String entity,String entityId,String eventId){
        if(actor.tenantId()==null)return;
        notify(actor.tenantId(),actor.userId(),eventId,event,"Action recorded: "+event,"An authorized "+entity+" action was recorded.");
        String payload=encode(Map.of("id",eventId,"type",event,"entity",entity,"entityId",entityId,"occurredAt",java.time.Instant.now().toString()));
        for(var endpoint:db.queryForList("SELECT id,events FROM webhook_endpoints WHERE tenant_id=? AND status='ACTIVE' AND direction='OUTBOUND'",actor.tenantId())){
            List<String> events=decode(endpoint.get("events").toString());if(!events.contains("*")&&!events.contains(event))continue;
            db.update("INSERT IGNORE INTO webhook_deliveries(id,tenant_id,endpoint_id,event_id,event_type,payload,next_attempt_at) VALUES (?,?,?,?,?,?,UTC_TIMESTAMP(6))",UUID.randomUUID().toString(),actor.tenantId(),endpoint.get("id"),eventId,event,payload);
        }
    }
    public void notify(String tenant,String user,String key,String event,String title,String body){
        var members=db.queryForList("SELECT u.email FROM memberships m JOIN users u ON u.id=m.user_id JOIN tenants t ON t.id=m.tenant_id WHERE m.tenant_id=? AND m.user_id=? AND m.status='ACTIVE' AND u.status='ACTIVE' AND t.status='ACTIVE'",tenant,user);if(members.isEmpty())return;
        var preferences=db.queryForList("SELECT in_app,email FROM notification_preferences WHERE tenant_id=? AND user_id=? AND event_type IN (?,'*') ORDER BY (event_type=?) DESC LIMIT 1",tenant,user,event,event);
        boolean inApp=preferences.isEmpty()||truth(preferences.getFirst().get("in_app"));boolean email=!preferences.isEmpty()&&truth(preferences.getFirst().get("email"));
        int inserted=0;if(inApp)inserted=db.update("INSERT IGNORE INTO notifications(id,tenant_id,user_id,event_key,title,body) VALUES (?,?,?,?,?,?)",UUID.randomUUID().toString(),tenant,user,key,title,body);
        if(inserted==1){Runnable send=()->stream.send(tenant,user,Map.of("title",title,"body",body,"eventKey",key));if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public void afterCommit(){send.run();}});else send.run();}
        if(inserted==1||!inApp){db.update("INSERT INTO notification_deliveries(id,tenant_id,user_id,event_key,channel,status) VALUES (?,?,?,?,?,?)",UUID.randomUUID().toString(),tenant,user,key,"IN_APP",inApp?"DELIVERED":"SUPPRESSED");
            if(email){mail.queue(members.getFirst().get("email").toString(),title,body);db.update("INSERT INTO notification_deliveries(id,tenant_id,user_id,event_key,channel,status) VALUES (?,?,?,?,?,'QUEUED')",UUID.randomUUID().toString(),tenant,user,key,"EMAIL");}}
    }
    private static boolean truth(Object v){return Boolean.TRUE.equals(v)||(v instanceof Number n&&n.intValue()!=0);}
    private String encode(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private List<String> decode(String v){try{return json.readValue(v,new TypeReference<List<String>>(){});}catch(Exception e){throw new IllegalStateException(e);}}
}
