package com.enterpriseos;

import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Explicit first-install provisioning. No sample data, reusable default
 * password or public registration.
 */
@RestController
@RequestMapping("/api/v1/setup")
public class SetupController {
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder passwords;
    private final AuditService audit;
    private final boolean enabled;

    public SetupController(JdbcTemplate jdbc, BCryptPasswordEncoder passwords, AuditService audit,
            @Value("${app.setup-enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.audit = audit;
        this.enabled = enabled;
    }

    @GetMapping
    public Map<String, Object> status() {
        return Map.of("required", enabled && jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class) == 0);
    }

    @PostMapping
    @Transactional
    public Map<String, Object> initialize(@RequestBody Map<String, Object> body) {
        if (!enabled)
            throw new ApiException(403, "Initial setup is disabled on this installation.");
        jdbc.queryForList("SELECT id FROM setup_guard WHERE id=1 FOR UPDATE");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class) != 0)
            throw new ApiException(409, "This installation has already been set up. Sign in instead.");
        String company = Inputs.text(body, "companyName", 180), ownerName = Inputs.text(body, "ownerName", 180);
        String ownerEmail = Inputs.email(body, "ownerEmail"), platformEmail = Inputs.email(body, "platformEmail");
        if (ownerEmail.equals(platformEmail))
            throw new ApiException(400, "Use different owner and platform administrator emails.");
        String ownerPassword = Inputs.password(Map.of("password", body.getOrDefault("ownerPassword", "")));
        String platformPassword = Inputs.password(Map.of("password", body.getOrDefault("platformPassword", "")));
        String timezone = Inputs.timezone(body), tenant = UUID.randomUUID().toString(),
                owner = UUID.randomUUID().toString(), platform = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO tenants(id,name,slug,status,timezone,created_at) VALUES (?,?,?,'ACTIVE',?,UTC_TIMESTAMP(6))",
                tenant, company, "workspace-" + tenant.substring(0, 8), timezone);
        jdbc.update("INSERT INTO organization_profiles(tenant_id,legal_name,email) VALUES (?,?,?)", tenant, company,
                ownerEmail);
        jdbc.update(
                "INSERT INTO users(id,name,email,password_hash,status,platform_role,created_at) VALUES (?,?,?,?,'ACTIVE',NULL,UTC_TIMESTAMP(6))",
                owner, ownerName, ownerEmail, passwords.encode(ownerPassword));
        jdbc.update(
                "INSERT INTO users(id,name,email,password_hash,status,platform_role,created_at) VALUES (?,'Platform Administrator',?,?,'ACTIVE','PLATFORM_ADMIN',UTC_TIMESTAMP(6))",
                platform, platformEmail, passwords.encode(platformPassword));
        jdbc.update("INSERT INTO memberships(id,tenant_id,user_id,role,status) VALUES (?,?,?,'ADMIN','ACTIVE')",
                UUID.randomUUID().toString(), tenant, owner);
        jdbc.update("UPDATE setup_guard SET completed_at=UTC_TIMESTAMP(6) WHERE id=1");
        audit.log(new Principal(owner, tenant, ownerName, ownerEmail, "ADMIN"), "INSTALLATION_INITIALIZED", "tenant",
                tenant);
        return Map.of("message", "Your company is ready. Sign in with your owner account.");
    }
}
