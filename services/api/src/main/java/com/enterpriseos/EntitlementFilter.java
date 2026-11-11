package com.enterpriseos;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component @Order(Ordered.HIGHEST_PRECEDENCE+15)
public class EntitlementFilter extends OncePerRequestFilter {
    private final EntitlementService entitlements;private final ObjectMapper json;
    public EntitlementFilter(EntitlementService entitlements,ObjectMapper json){this.entitlements=entitlements;this.json=json;}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws IOException,ServletException{
        if(req.getRequestURI().startsWith("/api/v1/org/")&&!Set.of("GET","HEAD","OPTIONS").contains(req.getMethod())&&req.getAttribute("principal") instanceof Principal p){
            try{var sub=entitlements.subscription(p.tenantId());if(!sub.isEmpty())entitlements.writable(sub);
                if(req.getRequestURI().startsWith("/api/v1/org/attendance")||req.getRequestURI().startsWith("/api/v1/org/leave"))entitlements.require(p,"HR",false);
            }catch(ApiException ex){res.setStatus(ex.status());res.setContentType("application/json");json.writeValue(res.getOutputStream(),Map.of("message",ex.getMessage(),"code","ENTITLEMENT_DENIED"));return;}
        }chain.doFilter(req,res);
    }
}
