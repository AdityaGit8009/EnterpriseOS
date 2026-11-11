package com.enterpriseos;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class EntitlementService {
    public static final Set<String> MODULES=Set.of("HR","PROJECTS","CRM","INVENTORY","FINANCE","PAYROLL","WORKFLOWS","DOCUMENTS","REPORTS","INTEGRATIONS");
    private final JdbcTemplate db;private final ObjectMapper json;private final FeatureFlagService flags;
    public EntitlementService(JdbcTemplate db,ObjectMapper json,FeatureFlagService flags){this.db=db;this.json=json;this.flags=flags;}
    public void require(Principal p,String module,boolean creation){
        if(p.tenantId()==null)throw new ApiException(403,"An organization membership is required.");
        flags.require(p.tenantId(),module);Map<String,Object> sub=subscription(p.tenantId());if(sub.isEmpty())return;
        writable(sub);
        try{List<String> allowed=json.readValue(sub.get("modules").toString(),new TypeReference<List<String>>(){});if(!allowed.contains(module)&&!allowed.contains("*"))throw new ApiException(403,"Your subscription does not include "+module+".");}
        catch(ApiException ex){throw ex;}catch(Exception ex){throw new IllegalStateException("Invalid subscription module policy",ex);}
        if(creation&&recordCount(p.tenantId())>=((Number)sub.get("record_limit")).longValue())throw new ApiException(409,"Your plan record quota has been reached. Existing data is preserved.");
    }
    public void checkSeat(String tenantId){Map<String,Object>s=subscription(tenantId);if(s.isEmpty())return;writable(s);if(seatCount(tenantId)>=((Number)s.get("seat_limit")).longValue())throw new ApiException(409,"Your subscription seat limit has been reached.");}
    public void checkInviteSeat(String tenantId){Map<String,Object>s=subscription(tenantId);if(s.isEmpty())return;writable(s);long pending=db.queryForObject("SELECT COUNT(*) FROM invitations WHERE tenant_id=? AND accepted_at IS NULL AND expires_at>UTC_TIMESTAMP(6)",Long.class,tenantId);if(seatCount(tenantId)+pending>=((Number)s.get("seat_limit")).longValue())throw new ApiException(409,"Your plan seats are allocated to active members or pending invitations.");}
    public long seatCount(String tenant){return db.queryForObject("SELECT COUNT(*) FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.tenant_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'",Long.class,tenant);}
    public long recordCount(String tenant){return db.queryForObject("SELECT (SELECT COUNT(*) FROM business_records WHERE tenant_id=?)+(SELECT COUNT(*) FROM business_entities WHERE tenant_id=?)+(SELECT COUNT(*) FROM documents WHERE tenant_id=? AND status<>'DELETED')+(SELECT COUNT(*) FROM workflow_definitions WHERE tenant_id=?)+(SELECT COUNT(*) FROM workforce_requests WHERE tenant_id=?)+(SELECT COUNT(*) FROM workforce_risks WHERE tenant_id=?)+(SELECT COUNT(*) FROM recurring_task_templates WHERE tenant_id=?)",Long.class,tenant,tenant,tenant,tenant,tenant,tenant,tenant);}
    public Map<String,Object> subscription(String tenant){var rows=db.queryForList("SELECT s.*,p.name AS plan_name,p.seat_limit,p.record_limit,p.modules,CASE WHEN s.status IN ('ACTIVE','TRIAL') AND (s.expires_at>UTC_TIMESTAMP(6) OR s.grace_until>UTC_TIMESTAMP(6)) THEN 1 ELSE 0 END AS writable FROM tenant_subscriptions s JOIN subscription_plans p ON p.id=s.plan_id WHERE s.tenant_id=?",tenant);return rows.isEmpty()?Map.of():rows.getFirst();}
    public void writable(Map<String,Object>s){if(((Number)s.get("writable")).intValue()!=1)throw new ApiException(403,"The subscription is read-only because it is suspended, cancelled or expired. Existing data is retained.");}
}
