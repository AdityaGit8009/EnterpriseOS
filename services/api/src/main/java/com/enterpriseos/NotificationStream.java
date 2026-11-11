package com.enterpriseos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class NotificationStream {
    private final org.springframework.jdbc.core.JdbcTemplate db;private final CustomAccessPolicy custom;private final NotificationVisibility visibility;
    public NotificationStream(org.springframework.jdbc.core.JdbcTemplate db,CustomAccessPolicy custom,NotificationVisibility visibility){this.db=db;this.custom=custom;this.visibility=visibility;}
    private final ConcurrentHashMap<String,CopyOnWriteArrayList<SseEmitter>> clients=new ConcurrentHashMap<>();
    SseEmitter connect(String tenant,String user){String key=tenant+":"+user;var emitter=new SseEmitter(60_000L);clients.computeIfAbsent(key,k->new CopyOnWriteArrayList<>()).add(emitter);Runnable remove=()->{var list=clients.get(key);if(list!=null){list.remove(emitter);if(list.isEmpty())clients.remove(key,list);}};emitter.onCompletion(remove);emitter.onTimeout(remove);emitter.onError(e->remove.run());try{emitter.send(SseEmitter.event().name("connected").data(Map.of("status","connected")));}catch(Exception ex){remove.run();}return emitter;}
    void send(String tenant,String user,Map<String,Object> notification){var list=clients.get(tenant+":"+user);if(list==null)return;var principals=db.query("SELECT u.name,u.email,m.role FROM memberships m JOIN users u ON u.id=m.user_id JOIN tenants t ON t.id=m.tenant_id WHERE m.tenant_id=? AND m.user_id=? AND m.status='ACTIVE' AND u.status='ACTIVE' AND t.status='ACTIVE'",(rs,n)->new Principal(user,tenant,rs.getString("name"),rs.getString("email"),rs.getString("role")),tenant,user);if(principals.isEmpty()||!custom.allowed(principals.getFirst(),"GET","/api/v1/org/notifications")){for(var emitter:list)emitter.complete();clients.remove(tenant+":"+user);return;}if(!visibility.allowed(principals.getFirst(),notification.get("eventKey").toString()))return;for(var emitter:list)try{emitter.send(SseEmitter.event().name("notification").data(notification));}catch(Exception ex){list.remove(emitter);emitter.complete();}}
}
