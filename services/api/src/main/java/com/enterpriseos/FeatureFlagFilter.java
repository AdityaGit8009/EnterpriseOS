package com.enterpriseos;

import java.io.IOException;
import java.util.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component @Order(Ordered.HIGHEST_PRECEDENCE+14)
public class FeatureFlagFilter extends OncePerRequestFilter {
    private final FeatureFlagService flags;private final ObjectMapper json;
    public FeatureFlagFilter(FeatureFlagService flags,ObjectMapper json){this.flags=flags;this.json=json;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{if(request.getAttribute("principal") instanceof Principal p&&p.tenantId()!=null){String module=module(request.getRequestURI());if(module!=null&&!flags.enabled(p.tenantId(),module)){response.setStatus(403);response.setContentType("application/json");json.writeValue(response.getOutputStream(),Map.of("message","This organization feature is disabled: "+module,"code","FEATURE_DISABLED"));return;}}chain.doFilter(request,response);}
    static String module(String path){if(!path.startsWith("/api/v1/org/"))return null;String p=path.substring("/api/v1/org/".length());if(p.startsWith("workflows/"))return "WORKFLOWS";if(p.startsWith("documents"))return "DOCUMENTS";if(p.startsWith("reports")||p.startsWith("dashboard/widgets"))return "REPORTS";if(p.startsWith("integrations/"))return "INTEGRATIONS";if(p.startsWith("attendance")||p.startsWith("leave"))return "HR";if(p.startsWith("records/")){String kind=p.substring(8).split("/")[0];return Set.of("employees","departments","branches","teams","designations","shifts","shiftAssignments","leavePolicies").contains(kind)?"HR":"PROJECTS";}if(p.startsWith("tasks/"))return "PROJECTS";if(p.startsWith("business/")){String b=p.substring(9);if(b.startsWith("records/")){String kind=b.substring(8).split("/")[0];if(Set.of("accounts","contacts","leads","pipelineStages","opportunities","activities","quotes","customerContracts").contains(kind))return "CRM";if(Set.of("items","warehouses","suppliers","purchaseRequests","purchaseOrders","goodsReceipts","goodsReturns","rfqs","vendorQuotes","purchaseContracts","reservations","cycleCounts").contains(kind))return "INVENTORY";if(Set.of("compensations","payrollRuns").contains(kind))return "PAYROLL";return "FINANCE";}if(b.startsWith("crm/")||b.startsWith("quotes/")||b.startsWith("leads/")||b.startsWith("opportunities/")||b.startsWith("customerContracts/"))return "CRM";if(b.startsWith("inventory/")||b.startsWith("purchasing/")||b.startsWith("procurement/")||b.startsWith("purchaseRequests/")||b.startsWith("purchaseOrders/")||b.startsWith("supplierQuotes/")||b.startsWith("supplierBills/"))return "INVENTORY";if(b.startsWith("payroll/"))return "PAYROLL";return "FINANCE";}return null;}
}
