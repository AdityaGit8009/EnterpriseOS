package com.enterpriseos;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
@RestController public class AuthenticationAuditController {
    private final JdbcTemplate db;public AuthenticationAuditController(JdbcTemplate db){this.db=db;}
    @GetMapping("/api/v1/platform/login-attempts") public List<Map<String,Object>> attempts(@RequestAttribute Principal principal,@RequestParam(defaultValue="0")int page){Inputs.requireRole(principal,"PLATFORM_ADMIN");if(page<0||page>10000)throw new ApiException(400,"Invalid page.");return db.queryForList("SELECT id,email_hash AS emailHash,ip_hash AS ipHash,outcome,created_at AS createdAt FROM authentication_attempts ORDER BY created_at DESC,id LIMIT 50 OFFSET ?",page*50);}
}
