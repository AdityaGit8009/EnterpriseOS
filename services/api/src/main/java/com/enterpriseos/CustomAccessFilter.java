package com.enterpriseos;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component @Order(Ordered.HIGHEST_PRECEDENCE+12)
public class CustomAccessFilter extends OncePerRequestFilter {
    private final JdbcTemplate db;private final ObjectMapper json;private final AntPathMatcher matcher=new AntPathMatcher();private final TransactionTemplate tx;
    public CustomAccessFilter(JdbcTemplate db,ObjectMapper json,PlatformTransactionManager manager){this.db=db;this.json=json;this.tx=new TransactionTemplate(manager);}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException {
        String path=request.getRequestURI();
        // Recurrence is deliberately a per-template batch; each item locks and rechecks its grant itself.
        boolean independentBatch=path.equals("/api/v1/org/workforce/recurring-tasks/run-due")&&request.getMethod().equals("POST");
        if(path.startsWith("/api/v1/org/")&&!independentBatch&&!java.util.Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())&&request.getAttribute("principal") instanceof Principal actor){
            ContentCachingResponseWrapper buffered=new ContentCachingResponseWrapper(response);
            try{tx.executeWithoutResult(status->{
                db.queryForList("SELECT id FROM tenants WHERE id=? FOR UPDATE",actor.tenantId());
                var current=db.queryForList("SELECT m.role FROM memberships m JOIN users u ON u.id=m.user_id JOIN tenants t ON t.id=m.tenant_id WHERE m.tenant_id=? AND m.user_id=? AND m.status='ACTIVE' AND u.status='ACTIVE' AND t.status='ACTIVE'",actor.tenantId(),actor.userId());
                try{
                    if(current.isEmpty()){denied(buffered,"Your organization access is no longer active.");return;}
                    request.setAttribute("principal",new Principal(actor.userId(),actor.tenantId(),actor.name(),actor.email(),current.getFirst().get("role").toString()));
                    permittedChain(request,buffered,chain);
                    if(buffered.getStatus()>=400)status.setRollbackOnly();
                }catch(IOException|ServletException ex){throw new FilterFailure(ex);}
            });}catch(FilterFailure ex){if(ex.getCause() instanceof IOException io)throw io;throw (ServletException)ex.getCause();}
            buffered.copyBodyToResponse();return;
        }
        permittedChain(request,response,chain);
    }
    private static class FilterFailure extends RuntimeException {FilterFailure(Exception cause){super(cause);}}
    private void denied(HttpServletResponse response,String message)throws IOException{response.setStatus(403);response.setContentType("application/json");json.writeValue(response.getOutputStream(),Map.of("code","ACCESS_DENIED","message",message));}
    private void permittedChain(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException {
        String path=request.getRequestURI();
        if(path.startsWith("/api/v1/org/")&&request.getAttribute("principal") instanceof Principal principal){
            var rows=db.queryForList("SELECT r.permissions FROM memberships m JOIN custom_roles r ON r.id=m.custom_role_id AND r.tenant_id=m.tenant_id WHERE m.tenant_id=? AND m.user_id=?",principal.tenantId(),principal.userId());
            if(!rows.isEmpty()&&!path.equals("/api/v1/org/my-permissions")){
                List<String> permissions=json.readValue(rows.getFirst().get("permissions").toString(),new TypeReference<List<String>>(){});String method=request.getMethod().equals("HEAD")?"GET":request.getMethod();
                boolean allowed=permissions.stream().anyMatch(permission->{int space=permission.indexOf(' ');return space>0&&method.equals(permission.substring(0,space))&&matcher.match(permission.substring(space+1),path);});
                if(!allowed){denied(response,"Your custom role does not include this action.");return;}
            }
        }
        chain.doFilter(request,response);
    }
}
