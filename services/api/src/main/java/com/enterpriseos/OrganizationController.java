package com.enterpriseos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class OrganizationController {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final MailService mail;
    private final EntitlementService entitlements;
    private final String baseUrl;

    public OrganizationController(JdbcTemplate jdbc, AuditService audit, MailService mail,
            EntitlementService entitlements, @Value("${app.public-base-url}") String baseUrl) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.mail = mail;
        this.entitlements = entitlements;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @GetMapping("/platform/tenants")
    public List<Map<String, Object>> tenants(@RequestAttribute Principal principal,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Inputs.requireRole(principal, "PLATFORM_ADMIN");
        int limit = pageSize(size), offset = offset(page, limit);
        return jdbc.queryForList(
                "SELECT t.id,t.name,t.slug,t.status,t.timezone,t.created_at AS createdAt,(SELECT COUNT(*) FROM memberships m WHERE m.tenant_id=t.id AND m.status='ACTIVE') AS activeUsers FROM tenants t WHERE t.name LIKE ? OR t.slug LIKE ? ORDER BY t.created_at DESC LIMIT ? OFFSET ?",
                pattern(q), pattern(q), limit, offset);
    }

    @PostMapping("/platform/tenants")
    @Transactional
    public Map<String, Object> createTenant(@RequestAttribute Principal principal,
            @RequestBody Map<String, Object> body) {
        Inputs.requireRole(principal, "PLATFORM_ADMIN");
        String name = Inputs.text(body, "name", 180),
                slug = Inputs.text(body, "slug", 80).toLowerCase(java.util.Locale.ROOT);
        if (!slug.matches("[a-z0-9][a-z0-9-]{1,78}[a-z0-9]"))
            throw new ApiException(400,
                    "Company slug must be 3–80 lowercase letters, digits or hyphens, without a leading/trailing hyphen.");
        String email = Inputs.email(body, "ownerEmail"), timezone = Inputs.timezone(body),
                id = UUID.randomUUID().toString();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email=? AND platform_role IS NOT NULL", Integer.class,
                email) > 0)
            throw new ApiException(400, "Use a separate organization-owner email address.");
        jdbc.update(
                "INSERT INTO tenants(id,name,slug,status,timezone,created_at) VALUES (?,?,?,'ACTIVE',?,UTC_TIMESTAMP(6))",
                id, name, slug, timezone);
        jdbc.update("INSERT INTO organization_profiles(tenant_id,legal_name,email) VALUES (?,?,?)", id, name, email);
        issueInvitation(principal, id, email, "ADMIN");
        audit.log(principal, "TENANT_CREATED", "tenant", id);
        return Map.of("id", id, "name", name, "slug", slug, "status", "ACTIVE", "timezone", timezone, "message",
                "Company created. Owner activation invitation queued.");
    }

    @PatchMapping("/platform/tenants/{id}/status")
    @Transactional
    public Map<String, Object> tenantStatus(@RequestAttribute Principal principal, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Inputs.requireRole(principal, "PLATFORM_ADMIN");
        String status = Inputs.text(body, "status", 20);
        if (!List.of("ACTIVE", "SUSPENDED").contains(status))
            throw new ApiException(400, "Status must be ACTIVE or SUSPENDED.");
        lockTenant(id);
        jdbc.update("UPDATE tenants SET status=? WHERE id=?", status, id);
        if ("SUSPENDED".equals(status))
            jdbc.update("UPDATE sessions SET revoked_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND revoked_at IS NULL", id);
        audit.log(principal, "TENANT_" + status, "tenant", id);
        return Map.of("id", id, "status", status);
    }

    @GetMapping("/org/profile")
    public Map<String, Object> profile(@RequestAttribute Principal principal) {
        var result = jdbc.queryForMap(
                "SELECT t.id,t.name,t.slug,t.status,t.timezone,p.legal_name AS legalName,p.email,p.phone,p.address,p.logo_key AS logoKey,p.currency,p.country,p.locale,p.fiscal_year_start_month AS fiscalYearStartMonth,p.gstin FROM tenants t JOIN organization_profiles p ON p.tenant_id=t.id WHERE t.id=?",
                principal.tenantId());
        result.put("logoKey",
                result.get("logoKey") == null || result.get("logoKey").toString().isBlank() ? "" : "/api/v1/org/logo");
        return result;
    }

    @PutMapping("/org/profile")
    @Transactional
    public Map<String, Object> updateProfile(@RequestAttribute Principal principal,
            @RequestBody Map<String, Object> body) {
        Inputs.requireRole(principal, "ADMIN");
        String name = Inputs.text(body, "name", 180), legal = Inputs.optional(body, "legalName", 180, name),
                email = Inputs.email(body, "email"), timezone = Inputs.timezone(body);
        String logo = Inputs.optional(body, "logoKey", 500, "/api/v1/org/logo");
        if (!logo.isEmpty() && !logo.equals("/api/v1/org/logo"))
            throw new ApiException(400,
                    "Upload a logo using the organization logo picker. Remote image URLs are not accepted.");
        lockActiveOrganization(principal);
        Map<String, Object> currentProfile = profile(principal);
        String currency = Inputs.optional(body, "currency", 3, currentProfile.get("currency").toString())
                .toUpperCase(java.util.Locale.ROOT);
        try {
            java.util.Currency.getInstance(currency);
        } catch (Exception ex) {
            throw new ApiException(400, "Choose a valid currency code.");
        }
        String country = Inputs.optional(body, "country", 2, currentProfile.get("country").toString())
                .toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of(java.util.Locale.getISOCountries()).contains(country))
            throw new ApiException(400, "Choose a valid country code.");
        String locale = Inputs.optional(body, "locale", 35, currentProfile.get("locale").toString());
        try {
            new java.util.Locale.Builder().setLanguageTag(locale).build();
        } catch (Exception ex) {
            throw new ApiException(400, "Choose a valid locale.");
        }
        int fiscalMonth;
        try {
            fiscalMonth = new java.math.BigDecimal(
                    body.getOrDefault("fiscalYearStartMonth", currentProfile.get("fiscalYearStartMonth")).toString())
                    .intValueExact();
        } catch (Exception ex) {
            throw new ApiException(400, "Fiscal year month must be 1 to 12.");
        }
        if (fiscalMonth < 1 || fiscalMonth > 12)
            throw new ApiException(400, "Fiscal year month must be 1 to 12.");
        String gstin = Inputs.optional(body, "gstin", 15, currentProfile.get("gstin").toString())
                .toUpperCase(java.util.Locale.ROOT);
        if (!gstin.isEmpty() && !gstin.matches("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]"))
            throw new ApiException(400, "GSTIN must have the 15-character registration format.");
        String storedLogo = jdbc.queryForObject("SELECT logo_key FROM organization_profiles WHERE tenant_id=?",
                String.class, principal.tenantId());
        logo = logo.isEmpty() ? "" : storedLogo;
        jdbc.update("UPDATE tenants SET name=?,timezone=? WHERE id=?", name, timezone, principal.tenantId());
        jdbc.update(
                "UPDATE organization_profiles SET legal_name=?,email=?,phone=?,address=?,logo_key=? WHERE tenant_id=?",
                legal, email, Inputs.optional(body, "phone", 40, ""), Inputs.optional(body, "address", 1000, ""), logo,
                principal.tenantId());
        jdbc.update(
                "UPDATE organization_profiles SET currency=?,country=?,locale=?,fiscal_year_start_month=?,gstin=? WHERE tenant_id=?",
                currency, country, locale, fiscalMonth, gstin, principal.tenantId());
        audit.log(principal, "ORGANIZATION_UPDATED", "tenant", principal.tenantId());
        return profile(principal);
    }

    @GetMapping("/org/users")
    public List<Map<String, Object>> users(@RequestAttribute Principal principal,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        int limit = pageSize(size), offset = offset(page, limit);
        // Minimal organization directory. Credentials and other tenants' memberships
        // are never selected.
        return jdbc.queryForList(
                "SELECT u.id,m.id AS membershipId,u.name,u.email,m.role,m.status,m.custom_role_id AS customRoleId,(SELECT r.name FROM custom_roles r WHERE r.id=m.custom_role_id AND r.tenant_id=m.tenant_id) AS customRoleName FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.tenant_id=? AND (u.name LIKE ? OR u.email LIKE ?) ORDER BY u.name LIMIT ? OFFSET ?",
                principal.tenantId(), pattern(q), pattern(q), limit, offset);
    }

    @PatchMapping("/org/users/{id}/status")
    @Transactional
    public Map<String, Object> userStatus(@RequestAttribute Principal principal, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Inputs.requireRole(principal, "ADMIN");
        String status = Inputs.text(body, "status", 20);
        if (!List.of("ACTIVE", "INACTIVE").contains(status))
            throw new ApiException(400, "Status must be ACTIVE or INACTIVE.");
        var membership = lockMembership(principal, id);
        if ("ACTIVE".equals(status) && !"ACTIVE".equals(membership.get("status")))
            entitlements.checkSeat(principal.tenantId());
        if ("INACTIVE".equals(status))
            guardLastAdmin(principal.tenantId(), membership);
        jdbc.update("UPDATE memberships SET status=? WHERE tenant_id=? AND user_id=?", status, principal.tenantId(),
                id);
        if ("INACTIVE".equals(status))
            jdbc.update(
                    "UPDATE sessions SET revoked_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND user_id=? AND revoked_at IS NULL",
                    principal.tenantId(), id);
        audit.log(principal, "MEMBER_" + status, "user", id);
        return Map.of("id", id, "status", status);
    }

    @PatchMapping("/org/users/{id}/role")
    @Transactional
    public Map<String, Object> userRole(@RequestAttribute Principal principal, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Inputs.requireRole(principal, "ADMIN");
        String role = Inputs.role(body);
        var membership = lockMembership(principal, id);
        if (!"ADMIN".equals(role))
            guardLastAdmin(principal.tenantId(), membership);
        jdbc.update("UPDATE memberships SET role=?,custom_role_id=NULL WHERE tenant_id=? AND user_id=?", role,
                principal.tenantId(), id);
        audit.log(principal, "MEMBER_ROLE_CHANGED", "user", id);
        return Map.of("id", id, "role", role);
    }

    @GetMapping("/org/invitations")
    public List<Map<String, Object>> invitations(@RequestAttribute Principal principal) {
        Inputs.requireRole(principal, "ADMIN");
        return jdbc.queryForList(
                "SELECT id,email,role,expires_at AS expiresAt,accepted_at AS acceptedAt,created_at AS createdAt,CASE WHEN accepted_at IS NOT NULL THEN 'ACCEPTED' WHEN expires_at<=UTC_TIMESTAMP(6) THEN 'EXPIRED' ELSE 'PENDING' END AS status FROM invitations WHERE tenant_id=? ORDER BY created_at DESC LIMIT 200",
                principal.tenantId());
    }

    @PostMapping("/org/invitations")
    @Transactional
    public Map<String, Object> invite(@RequestAttribute Principal principal, @RequestBody Map<String, Object> body) {
        Inputs.requireRole(principal, "ADMIN");
        String email = Inputs.email(body, "email"), role = Inputs.role(body);
        lockActiveOrganization(principal);
        if (jdbc.queryForObject(
                "SELECT COUNT(*) FROM invitations WHERE tenant_id=? AND email=? AND accepted_at IS NULL AND expires_at>UTC_TIMESTAMP(6)",
                Integer.class, principal.tenantId(), email) == 0)
            entitlements.checkInviteSeat(principal.tenantId());
        if (jdbc.queryForObject(
                "SELECT COUNT(*) FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.tenant_id=? AND u.email=?",
                Integer.class, principal.tenantId(), email) > 0)
            throw new ApiException(409,
                    "This user already belongs to this organization. Manage their existing membership.");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email=? AND platform_role IS NOT NULL", Integer.class,
                email) > 0)
            throw new ApiException(400,
                    "Use an organization account email rather than a platform administrator email.");
        String id = issueInvitation(principal, principal.tenantId(), email, role);
        audit.log(principal, "INVITATION_CREATED", "invitation", id);
        return Map.of("id", id, "email", email, "role", role, "message", "Invitation queued for delivery.");
    }

    @GetMapping("/org/audit-events")
    public List<Map<String, Object>> auditEvents(@RequestAttribute Principal principal,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        Inputs.requireRole(principal, "ADMIN", "AUDITOR");
        int limit = pageSize(size);
        return jdbc.queryForList(
                "SELECT id,actor_id AS actorId,actor_name AS actorName,action,entity,entity_id AS entityId,created_at AS createdAt FROM audit_events WHERE tenant_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
                principal.tenantId(), limit, offset(page, limit));
    }

    @GetMapping("/org/dashboard")
    public Map<String, Object> dashboard(@RequestAttribute Principal principal) {
        var result = new LinkedHashMap<String, Object>();
        result.put("profile", profile(principal));
        result.put("activeUsers",
                jdbc.queryForObject("SELECT COUNT(*) FROM memberships WHERE tenant_id=? AND status='ACTIVE'",
                        Long.class, principal.tenantId()));
        result.put("pendingInvites", jdbc.queryForObject(
                "SELECT COUNT(*) FROM invitations WHERE tenant_id=? AND accepted_at IS NULL AND expires_at>UTC_TIMESTAMP(6)",
                Long.class, principal.tenantId()));
        boolean auditAccess = List.of("ADMIN", "AUDITOR").contains(principal.role());
        result.put("recentActivities", auditAccess ? jdbc.queryForList(
                "SELECT actor_name AS actorName,action,entity,entity_id AS entityId,created_at AS createdAt FROM audit_events WHERE tenant_id=? ORDER BY created_at DESC LIMIT 8",
                principal.tenantId())
                : jdbc.queryForList(
                        "SELECT actor_name AS actorName,action,entity,entity_id AS entityId,created_at AS createdAt FROM audit_events WHERE tenant_id=? AND actor_id=? ORDER BY created_at DESC LIMIT 8",
                        principal.tenantId(), principal.userId()));
        return result;
    }

    @GetMapping("/org/roles")
    public List<Map<String, Object>> roles(@RequestAttribute Principal principal) {
        return List.of(Map.of("role", "ADMIN", "description",
                "Organization administration, workforce, approvals, projects and audit; cannot remove the final active admin."),
                Map.of("role", "HR", "description",
                        "Employee records, organization structure, shifts, attendance and leave administration; no account-role administration."),
                Map.of("role", "MANAGER", "description",
                        "Authorized team approvals and project operations; restricted HR personal fields."),
                Map.of("role", "MEMBER", "description",
                        "Personal employee profile, attendance, leave and assigned project/task access."),
                Map.of("role", "AUDITOR", "description",
                        "Read-only authorized records and audit history; no business mutations."),
                Map.of("role", "SALES", "description", "Customer, pipeline and sales document operations."),
                Map.of("role", "PROCUREMENT", "description", "Suppliers, purchasing, warehouse and stock operations."),
                Map.of("role", "FINANCE", "description",
                        "Invoices, payment records, expenses and authorized financial review."),
                Map.of("role", "PAYROLL", "description",
                        "Compensation and payroll preparation with separate checker approval."));
    }

    private String issueInvitation(Principal actor, String tenantId, String email, String role) {
        String token = Tokens.issue(), id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO invitations(id,tenant_id,email,role,token_hash,expires_at,invited_by,created_at) VALUES (?,?,?,?,?,TIMESTAMPADD(HOUR,48,UTC_TIMESTAMP(6)),?,UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE role=VALUES(role),token_hash=VALUES(token_hash),expires_at=VALUES(expires_at),accepted_at=NULL,invited_by=VALUES(invited_by),created_at=VALUES(created_at)",
                id, tenantId, email, role, Tokens.hash(token), actor.userId());
        String actualId = jdbc.queryForObject("SELECT id FROM invitations WHERE tenant_id=? AND email=?", String.class,
                tenantId, email);
        mail.queue(email, "Your EnterpriseOS organization invitation",
                "You have been invited to an EnterpriseOS organization as " + role
                        + ".\n\nAccept this single-use invitation within 48 hours:\n" + baseUrl + "/?flow=invite&token="
                        + token
                        + "\n\nFor a new account, set a password of at least 12 characters. If you already have an EnterpriseOS account, use your current password; an invitation never changes an existing account's password.");
        return actualId;
    }

    private void lockTenant(String tenantId) {
        if (jdbc.queryForList("SELECT id FROM tenants WHERE id=? FOR UPDATE", tenantId).isEmpty())
            throw new ApiException(404, "Organization not found.");
    }

    private void lockActiveOrganization(Principal actor) {
        var tenants = jdbc.queryForList("SELECT status FROM tenants WHERE id=? FOR UPDATE", actor.tenantId());
        if (tenants.isEmpty() || !"ACTIVE".equals(tenants.getFirst().get("status")))
            throw new ApiException(403, "Organization is no longer active.");
        var members = jdbc.queryForList(
                "SELECT m.role FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.tenant_id=? AND m.user_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'",
                actor.tenantId(), actor.userId());
        if (members.isEmpty() || !actor.role().equals(members.getFirst().get("role")))
            throw new ApiException(403, "Your access changed. Reload and sign in again.");
    }

    private Map<String, Object> lockMembership(Principal actor, String userId) {
        // Serialize all final-admin checks on the tenant row, including concurrent
        // removals.
        lockActiveOrganization(actor);
        var rows = jdbc.queryForList("SELECT * FROM memberships WHERE tenant_id=? AND user_id=? FOR UPDATE",
                actor.tenantId(), userId);
        if (rows.isEmpty())
            throw new ApiException(404, "Organization user not found.");
        return rows.getFirst();
    }

    private void guardLastAdmin(String tenantId, Map<String, Object> member) {
        if ("ADMIN".equals(member.get("role")) && "ACTIVE".equals(member.get("status"))) {
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.tenant_id=? AND m.role='ADMIN' AND m.status='ACTIVE' AND u.status='ACTIVE'",
                    Long.class, tenantId);
            if (count == null || count <= 1)
                throw new ApiException(409,
                        "The final active organization administrator cannot be removed or demoted.");
        }
    }

    private static int pageSize(int size) {
        if (size < 1 || size > 200)
            throw new ApiException(400, "Page size must be between 1 and 200.");
        return size;
    }

    private static int offset(int page, int size) {
        if (page < 0 || page > 100000)
            throw new ApiException(400, "Page number is invalid.");
        return page * size;
    }

    private static String pattern(String q) {
        if (q.length() > 180)
            throw new ApiException(400, "Search is too long.");
        return "%" + q + "%";
    }
}
