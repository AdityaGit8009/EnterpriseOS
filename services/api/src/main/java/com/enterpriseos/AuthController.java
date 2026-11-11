package com.enterpriseos;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder passwords;
    private final AuditService audit;
    private final MailService mail;
    private final EntitlementService entitlements;
    private final MfaService mfa;
    private final PasswordPolicyService passwordPolicy;
    private final AuthenticationEvents authenticationEvents;
    private final TransactionTemplate loginTransactions;
    private record LoginResult(Principal principal,String token) {}
    private final boolean secure;
    private final int sessionHours;
    private final String baseUrl;
    private final String dummyHash;
    private final ConcurrentHashMap<String, Attempts> attempts = new ConcurrentHashMap<>();

    private record Attempts(int count, long until) {
    }

    public AuthController(JdbcTemplate jdbc, BCryptPasswordEncoder passwords, AuditService audit, MailService mail,
            EntitlementService entitlements, MfaService mfa, PasswordPolicyService passwordPolicy,
            AuthenticationEvents authenticationEvents, PlatformTransactionManager transactionManager,
            @Value("${app.secure-cookies}") boolean secure, @Value("${app.session-hours}") int sessionHours,
            @Value("${app.public-base-url}") String baseUrl) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.audit = audit;
        this.mail = mail;
        this.entitlements = entitlements;
        this.mfa = mfa;
        this.passwordPolicy = passwordPolicy;
        this.authenticationEvents = authenticationEvents;
        this.loginTransactions = new TransactionTemplate(transactionManager);
        this.secure = secure;
        this.sessionHours = Math.max(1, Math.min(24, sessionHours));
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        dummyHash = passwords.encode(Tokens.issue());
    }

    @PostMapping("/auth/login")
    public Principal login(@RequestBody Map<String,Object> body,HttpServletRequest request,HttpServletResponse response) {
        String email=Inputs.email(body,"email");Object value=body.get("password");
        if(!(value instanceof String password)||password.length()>256)throw new ApiException(400,"Enter your password.");
        String key=Tokens.hash(email+"|"+request.getRemoteAddr());
        try { limit(key,8); } catch(ApiException ex) { authenticationEvents.record(email,request.getRemoteAddr(),"THROTTLED");throw ex; }
        String[] outcome={"FAILED"};LoginResult result;
        try {
            result=loginTransactions.execute(status->authenticate(body,request,email,password,outcome));
        } catch(ApiException ex) {
            // This executes after authentication rollback/connection release, never as a nested pool borrower.
            authenticationEvents.record(email,request.getRemoteAddr(),outcome[0]);throw ex;
        }
        if(result==null)throw new IllegalStateException("Authentication did not produce a session");
        attempts.remove(key);
        authenticationEvents.record(email,request.getRemoteAddr(),"SUCCESS");
        // No cookie is issued until authentication and its audit transaction have committed.
        cookie(response,result.token(),Duration.ofHours(sessionHours));
        return result.principal();
    }
    private LoginResult authenticate(Map<String,Object> body,HttpServletRequest request,String email,String password,String[] outcome) {
        var users = jdbc.queryForList("SELECT * FROM users WHERE email=? FOR UPDATE", email);
        String stored = users.isEmpty() ? dummyHash : users.getFirst().get("password_hash").toString();
        boolean valid = passwords.matches(password, stored);
        if (users.isEmpty() || !valid || !"ACTIVE".equals(users.getFirst().get("status"))) {
            outcome[0] = "FAILED";
            throw new ApiException(401, "Email or password is incorrect, or access is inactive.");
        }
        var user = users.getFirst();
        String userId = user.get("id").toString(), tenantId = null, role;
        try {
            mfa.verifyLogin(userId, Inputs.optional(body, "otp", 40, ""));
        } catch (ApiException ex) {
            outcome[0] = "MFA_FAILED";
            throw ex;
        }
        String requestedTenant = Inputs.optional(body, "tenantId", 36, "");
        if ("PLATFORM_ADMIN".equals(user.get("platform_role")) && requestedTenant.isBlank()) {
            role = "PLATFORM_ADMIN";
        } else {
            var memberships = jdbc.queryForList(
                    "SELECT m.tenant_id,m.role FROM memberships m JOIN tenants t ON t.id=m.tenant_id WHERE m.user_id=? AND m.status='ACTIVE' AND t.status='ACTIVE' ORDER BY m.tenant_id",
                    userId);
            if (!requestedTenant.isBlank())
                memberships = memberships.stream().filter(m -> requestedTenant.equals(m.get("tenant_id"))).toList();
            if (memberships.isEmpty()) {
                outcome[0] = "NO_ACCESS";
                throw new ApiException(403, "No active organization access is available.");
            }
            tenantId = memberships.getFirst().get("tenant_id").toString();
            role = memberships.getFirst().get("role").toString();
        }
        Principal principal = new Principal(userId, tenantId, user.get("name").toString(), email, role);
        String token = Tokens.issue();
        jdbc.update(
                "INSERT INTO sessions(id,token_hash,user_id,tenant_id,created_at,expires_at) VALUES (?,?,?,?,UTC_TIMESTAMP(6),?)",
                UUID.randomUUID().toString(), Tokens.hash(token), userId, tenantId,
                Timestamp.from(Instant.now().plus(Duration.ofHours(sessionHours))));
        String agent = java.util.Objects.toString(request.getHeader("User-Agent"), "Unknown client")
                .replaceAll("[\\p{Cntrl}]", "");
        if (agent.length() > 200)
            agent = agent.substring(0, 200);
        jdbc.update("UPDATE sessions SET user_agent=?,client_ip_hash=? WHERE token_hash=?", agent,
                Tokens.hash(request.getRemoteAddr()), Tokens.hash(token));
        audit.log(principal, "AUTH_LOGIN", "session", userId);
        return new LoginResult(principal,token);
    }

    @PostMapping("/auth/logout")
    @Transactional
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = AuthFilter.sessionCookie(request);
        if (token != null && token.length() <= 256)
            jdbc.update("UPDATE sessions SET revoked_at=UTC_TIMESTAMP(6) WHERE token_hash=? AND revoked_at IS NULL",
                    Tokens.hash(token));
        cookie(response, "", Duration.ZERO);
        return Map.of("message", "Signed out.");
    }

    @GetMapping("/me")
    public Principal me(@RequestAttribute Principal principal) {
        return principal;
    }

    @PostMapping("/auth/refresh")
    @Transactional
    public Map<String, Object> refresh(@RequestAttribute Principal principal, HttpServletRequest request,
            HttpServletResponse response) {
        String old = AuthFilter.sessionCookie(request);
        var rows = jdbc.queryForList(
                "SELECT id FROM sessions WHERE token_hash=? AND user_id=? AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP(6) AND COALESCE(authenticated_at,created_at)>TIMESTAMPADD(HOUR,-24,UTC_TIMESTAMP(6)) FOR UPDATE",
                Tokens.hash(old), principal.userId());
        if (rows.isEmpty())
            throw new ApiException(401, "Session cannot be renewed. Sign in again.");
        String token = Tokens.issue(), id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO sessions(id,token_hash,user_id,tenant_id,created_at,expires_at,authenticated_at,user_agent,client_ip_hash) SELECT ?,?,user_id,tenant_id,UTC_TIMESTAMP(6),LEAST(TIMESTAMPADD(HOUR,?,UTC_TIMESTAMP(6)),TIMESTAMPADD(HOUR,24,COALESCE(authenticated_at,created_at))),COALESCE(authenticated_at,created_at),user_agent,client_ip_hash FROM sessions WHERE id=?",
                id, Tokens.hash(token), sessionHours, rows.getFirst().get("id"));
        jdbc.update("UPDATE sessions SET revoked_at=UTC_TIMESTAMP(6) WHERE id=?", rows.getFirst().get("id"));
        var expiry = jdbc.queryForObject("SELECT expires_at FROM sessions WHERE id=?", Timestamp.class, id).toInstant();
        cookie(response, token, Duration.between(Instant.now(), expiry));
        audit.log(principal, "SESSION_ROTATED", "session", id);
        return Map.of("message", "Session renewed. The previous cookie is no longer valid.", "expiresAt", expiry);
    }

    @PutMapping("/me/profile")
    @Transactional
    public Principal updatePersonalProfile(@RequestAttribute Principal principal,
            @RequestBody Map<String, Object> body) {
        String name = Inputs.text(body, "name", 180);
        jdbc.update("UPDATE users SET name=? WHERE id=? AND status='ACTIVE'", name, principal.userId());
        Principal updated = new Principal(principal.userId(), principal.tenantId(), name, principal.email(),
                principal.role());
        audit.log(updated, "PERSONAL_PROFILE_UPDATED", "user", principal.userId());
        return updated;
    }

    @PostMapping("/auth/change-password")
    @Transactional
    public Map<String, Object> changePassword(@RequestAttribute Principal principal,
            @RequestBody Map<String, Object> body, HttpServletResponse response) {
        Map<String, Object> passwordFields = new java.util.LinkedHashMap<>(body);
        if (body.containsKey("newPassword"))
            passwordFields.put("password", body.get("newPassword"));
        String password = Inputs.password(passwordFields);
        Object current = body.get("currentPassword");
        if (!(current instanceof String old) || old.length() > 256)
            throw new ApiException(400, "Enter your current password.");
        String stored = jdbc.queryForObject("SELECT password_hash FROM users WHERE id=? FOR UPDATE", String.class,
                principal.userId());
        if (!passwords.matches(old, stored))
            throw new ApiException(403, "Current password is incorrect.");
        passwordPolicy.enforceAndRemember(principal.userId(), password, stored);
        jdbc.update("UPDATE users SET password_hash=? WHERE id=?", passwords.encode(password), principal.userId());
        jdbc.update("UPDATE sessions SET revoked_at=UTC_TIMESTAMP(6) WHERE user_id=? AND revoked_at IS NULL",
                principal.userId());
        jdbc.update("UPDATE password_reset_tokens SET used_at=UTC_TIMESTAMP(6) WHERE user_id=? AND used_at IS NULL",
                principal.userId());
        audit.log(principal, "PASSWORD_CHANGED", "user", principal.userId());
        cookie(response, "", Duration.ZERO);
        return Map.of("message", "Password changed. Sign in again on all devices.");
    }

    @GetMapping("/me/sessions")
    public java.util.List<Map<String, Object>> sessions(@RequestAttribute Principal principal,
            HttpServletRequest request) {
        String current = AuthFilter.sessionCookie(request);
        String currentHash = current == null ? "" : Tokens.hash(current);
        return jdbc.query(
                "SELECT id,tenant_id,created_at,expires_at,token_hash,user_agent FROM sessions WHERE user_id=? AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP(6) ORDER BY created_at DESC LIMIT 100",
                (rs, row) -> {
                    Map<String, Object> view = new java.util.LinkedHashMap<>();
                    view.put("id", rs.getString("id"));
                    view.put("tenantId", rs.getString("tenant_id"));
                    view.put("createdAt", rs.getTimestamp("created_at").toInstant());
                    view.put("expiresAt", rs.getTimestamp("expires_at").toInstant());
                    view.put("userAgent", rs.getString("user_agent"));
                    view.put("current", currentHash.equals(rs.getString("token_hash")));
                    return view;
                }, principal.userId());
    }

    @DeleteMapping("/me/sessions/{id}")
    @Transactional
    public Map<String, Object> revokeSession(@RequestAttribute Principal principal, @PathVariable String id,
            HttpServletRequest request, HttpServletResponse response) {
        String current = AuthFilter.sessionCookie(request);
        var sessions = jdbc.queryForList(
                "SELECT token_hash FROM sessions WHERE id=? AND user_id=? AND revoked_at IS NULL FOR UPDATE", id,
                principal.userId());
        if (sessions.isEmpty())
            throw new ApiException(404, "Session not found.");
        jdbc.update("UPDATE sessions SET revoked_at=UTC_TIMESTAMP(6) WHERE id=? AND user_id=?", id, principal.userId());
        audit.log(principal, "SESSION_REVOKED", "session", id);
        boolean isCurrent = current != null && Tokens.hash(current).equals(sessions.getFirst().get("token_hash"));
        if (isCurrent)
            cookie(response, "", Duration.ZERO);
        return Map.of("message", "Session revoked.", "current", isCurrent);
    }

    @DeleteMapping("/me/sessions")
    @Transactional
    public Map<String, Object> revokeAllSessions(@RequestAttribute Principal principal, HttpServletResponse response) {
        int revoked = jdbc.update(
                "UPDATE sessions SET revoked_at=UTC_TIMESTAMP(6) WHERE user_id=? AND revoked_at IS NULL",
                principal.userId());
        cookie(response, "", Duration.ZERO);
        audit.log(principal, "ALL_SESSIONS_REVOKED", "user", principal.userId());
        return Map.of("message", "All sessions have been revoked. Sign in again.", "revoked", revoked);
    }

    @PostMapping("/auth/forgot-password")
    @Transactional
    public Map<String, Object> forgot(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String email = Inputs.email(body, "email");
        limit("reset:" + Tokens.hash(request.getRemoteAddr()), 12);
        var users = jdbc.queryForList("SELECT id,name FROM users WHERE email=? AND status='ACTIVE' FOR UPDATE", email);
        if (!users.isEmpty()) {
            String id = users.getFirst().get("id").toString(), token = Tokens.issue();
            jdbc.update("UPDATE password_reset_tokens SET used_at=UTC_TIMESTAMP(6) WHERE user_id=? AND used_at IS NULL",
                    id);
            jdbc.update(
                    "INSERT INTO password_reset_tokens(id,user_id,token_hash,expires_at,created_at) VALUES (?,?,?,TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)),UTC_TIMESTAMP(6))",
                    UUID.randomUUID().toString(), id, Tokens.hash(token));
            mail.queue(email, "Reset your EnterpriseOS password",
                    "A password reset was requested for your account. This single-use link expires in one hour:\n\n"
                            + baseUrl + "/?flow=reset&token=" + token
                            + "\n\nIf you did not request it, ignore this email.");
        }
        return Map.of("message", "If this account is eligible, password reset instructions will be emailed.");
    }

    @PostMapping("/auth/reset-password")
    @Transactional
    public Map<String, Object> reset(@RequestBody Map<String, Object> body) {
        String raw = Inputs.text(body, "token", 256), password = Inputs.password(body);
        var tokens = jdbc.queryForList(
                "SELECT r.id,r.user_id,u.name,u.email FROM password_reset_tokens r JOIN users u ON u.id=r.user_id WHERE r.token_hash=? AND r.used_at IS NULL AND r.expires_at>UTC_TIMESTAMP(6) AND u.status='ACTIVE' FOR UPDATE",
                Tokens.hash(raw));
        if (tokens.isEmpty())
            throw new ApiException(400, "Reset link is invalid, expired or already used.");
        var token = tokens.getFirst();
        String userId = token.get("user_id").toString();
        passwordPolicy.enforceAndRemember(userId, password,
                jdbc.queryForObject("SELECT password_hash FROM users WHERE id=? FOR UPDATE", String.class, userId));
        jdbc.update("UPDATE users SET password_hash=? WHERE id=?", passwords.encode(password), userId);
        jdbc.update("UPDATE password_reset_tokens SET used_at=UTC_TIMESTAMP(6) WHERE user_id=? AND used_at IS NULL", userId);
        jdbc.update("UPDATE sessions SET revoked_at=UTC_TIMESTAMP(6) WHERE user_id=? AND revoked_at IS NULL", userId);
        audit.log(new Principal(userId, null, token.get("name").toString(), token.get("email").toString(), "MEMBER"), "PASSWORD_RESET", "user", userId);
        return Map.of("message", "Password reset. Sign in with your new password.");
    }
    @PostMapping("/invitations/accept")
                
    @Transactional
    public Map<String,Object> accept(@RequestBody Map<String,Object> body) {
                
        String raw = Inputs.text(body, "token", 256), name = Inputs.text(body, "name", 180), password = Inputs.password(body);
        var invites = jdbc.queryForList("SELECT i.* FROM invitations i JOIN tenants t ON t.id=i.tenant_id WHERE i.token_hash=? AND i.accepted_at IS NULL AND i.expires_at>UTC_TIMESTAMP(6) AND t.status='ACTIVE' FOR UPDATE", Tokens.hash(raw));
        if (invites.isEmpty()) throw new ApiException(400, "Invitation is invalid, expired or already used.");
        var invite = invites.getFirst(); String email = invite.get("email").toString(), tenantId = invite.get("tenant_id").toString();
        entitlements.checkSeat(tenantId);
        var existing = jdbc.queryForList("SELECT * FROM users WHERE email=? FOR UPDATE", email);
        String userId;
                
                
        if (existing.isEmpty()) {
            userId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO users(id,name,email,password_hash,status,created_at) VALUES (?,?,?,?,'ACTIVE',UTC_TIMESTAMP(6))", userId, name, email, passwords.encode(password));
        } else {
            var user = existing.getFirst();
            if (!"ACTIVE".equals(user.get("status")) || user.get("platform_role") != null || !passwords.matches(password, user.get("password_hash").toString()))
                throw new ApiException(403, "An existing account must use its current password. Use password reset if needed.");
            userId = user.get("id").toString();
                    
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM memberships WHERE tenant_id=? AND user_id=?", Integer.class, tenantId, userId) > 0)
            throw new ApiException(409, "This user already has an organization membership.");
        jdbc.update("INSERT INTO memberships(id,tenant_id,user_id,role,status) VALUES (?,?,?,?,'ACTIVE')", UUID.randomUUID().toString(), tenantId, userId, invite.get("role"));
        jdbc.update("UPDATE invitations SET accepted_at=UTC_TIMESTAMP(6) WHERE id=?", invite.get("id"));
        audit.log(new Principal(userId, tenantId, name, email, invite.get("role").toString()), "INVITATION_ACCEPTED", "invitation", invite.get("id").toString());
        return Map.of("message", "Account activated. Sign in to continue.");
                
    }
    private void cookie(HttpServletResponse response, String token, Duration duration) {
                
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("EOS_SESSION", token).httpOnly(true).secure(secure).sameSite("Lax").path("/").maxAge(duration).build().toString());
    }
                
    private void limit(String key, int max) {
        long now = System.currentTimeMillis();
        if (attempts.size() > 10000) attempts.entrySet().removeIf(e -> e.getValue().until < now);
        Attempts current = attempts.compute(key, (k, previous) -> previous == null || previous.until < now 
                ? new Attempts(1, now + 15 * 60_000L) : new Attempts(previous.count + 1, previous.until));
        if (current.count > max) throw new ApiException(429, "Too many attempts. Try again after 15 minutes.");
    }
}

            
                
                        
            
