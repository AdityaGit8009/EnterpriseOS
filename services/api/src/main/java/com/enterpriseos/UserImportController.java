package com.enterpriseos;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/org/user-import")
public class UserImportController {
    private final JdbcTemplate db;private final OrganizationController organization;
    public UserImportController(JdbcTemplate db,OrganizationController organization){this.db=db;this.organization=organization;}
    @PostMapping("/preview") public Map<String,Object> preview(@RequestAttribute Principal principal,@RequestBody Map<String,Object> body){Inputs.requireRole(principal,"ADMIN");return validate(principal,body);}
    @PostMapping @Transactional public Map<String,Object> commit(@RequestAttribute Principal principal,@RequestBody Map<String,Object> body){
        Inputs.requireRole(principal,"ADMIN");db.queryForList("SELECT id FROM tenants WHERE id=? FOR UPDATE",principal.tenantId());
        Map<String,Object> preview=validate(principal,body);if(!(boolean)preview.get("valid"))throw new ApiException(400,"Correct import validation errors before importing.");
        List<Map<String,Object>> imported=new ArrayList<>();for(Object row:(List<?>)body.get("rows")){@SuppressWarnings("unchecked")Map<String,Object> item=(Map<String,Object>)row;imported.add(organization.invite(principal,item));}
        return Map.of("imported",imported.size(),"invitations",imported,"message","Validated invitations queued. Existing memberships were not modified.");
    }
    private Map<String,Object> validate(Principal p,Map<String,Object> body){
        if(!(body.get("rows") instanceof List<?> rows)||rows.isEmpty()||rows.size()>200)throw new ApiException(400,"Provide between 1 and 200 invitation rows.");
        List<Map<String,Object>> errors=new ArrayList<>();Set<String> emails=new HashSet<>();int number=0;
        for(Object value:rows){number++;try{
            if(!(value instanceof Map<?,?> raw))throw new ApiException(400,"Each row must contain email and role.");
            Map<String,Object> row=new HashMap<>();raw.forEach((k,v)->row.put(String.valueOf(k),v));
            String email=Inputs.email(row,"email");Inputs.role(row);
            if(!emails.add(email))throw new ApiException(400,"Duplicate email within import.");
            if(db.queryForObject("SELECT COUNT(*) FROM users u LEFT JOIN memberships m ON m.user_id=u.id AND m.tenant_id=? WHERE u.email=? AND (u.platform_role IS NOT NULL OR m.id IS NOT NULL)",Long.class,p.tenantId(),email)>0)throw new ApiException(400,"Email belongs to an existing member or platform account.");
        }catch(ApiException ex){errors.add(Map.of("row",number,"message",ex.getMessage()));}}
        return Map.of("valid",errors.isEmpty(),"total",rows.size(),"errors",errors);
    }
}
