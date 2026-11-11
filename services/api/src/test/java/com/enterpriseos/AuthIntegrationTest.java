package com.enterpriseos;

import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.Cookie;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.condition.EnabledIf;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = { "app.seed-demo=false", "APP_MAIL_POLL_MS=3600000",
        "APP_ENCRYPTION_KEY=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" })
@AutoConfigureMockMvc
@EnabledIf("com.enterpriseos.TestDatabase#available")
class AuthIntegrationTest {
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestDatabase.configure(registry);
        registry.add("app.logo-dir", () -> "./target/test-logos");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;
    @Autowired
    BCryptPasswordEncoder encoder;
    @org.springframework.boot.test.mock.mockito.MockBean
    DnsTxtResolver dns;
    String tenant, foreignTenant, admin, member, email;
    private static final String PASSWORD = "DevelopmentPass!123";

    @BeforeEach
    void setup() {
        tenant = UUID.randomUUID().toString();
        foreignTenant = UUID.randomUUID().toString();
        for (String t : java.util.List.of(tenant, foreignTenant)) {
            jdbc.update(
                    "INSERT INTO tenants(id,name,slug,status,timezone,created_at) VALUES (?, 'Test', ?, 'ACTIVE','UTC',UTC_TIMESTAMP(6))",
                    t, t);
            jdbc.update(
                    "INSERT INTO organization_profiles(tenant_id,legal_name,email) VALUES (?,'Test','owner@example.test')",
                    t);
        }
        admin = createUser(tenant, "ADMIN");
        member = createUser(tenant, "MEMBER");
        email = jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, admin);
    }

    private String createUser(String t, String role) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO users(id,name,email,password_hash,status,created_at) VALUES (?,?,?,?,'ACTIVE',UTC_TIMESTAMP(6))",
                id, role, id + "@example.test", encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO memberships(id,tenant_id,user_id,role,status) VALUES (?,?,?,?,'ACTIVE')",
                UUID.randomUUID().toString(), t, id, role);
        return id;
    }

    private Cookie login(String accountEmail) throws Exception {
        var result = mvc
                .perform(post("/api/v1/auth/login").header("Origin", "http://localhost:5173")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", accountEmail, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        String header = result.getResponse().getHeader("Set-Cookie");
        assertNotNull(header);
        assertTrue(header.contains("HttpOnly"));
        return new Cookie("EOS_SESSION", header.substring("EOS_SESSION=".length(), header.indexOf(';')));
    }

    @Test
    void membershipDeactivationAndTenantSuspensionRejectOlderSessions() throws Exception {
        Cookie session = login(email);
        mvc.perform(get("/api/v1/me").cookie(session)).andExpect(status().isOk());
        jdbc.update("UPDATE memberships SET status='INACTIVE' WHERE tenant_id=? AND user_id=?", tenant, admin);
        mvc.perform(get("/api/v1/me").cookie(session)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE memberships SET status='ACTIVE' WHERE tenant_id=? AND user_id=?", tenant, admin);
        jdbc.update("UPDATE tenants SET status='SUSPENDED' WHERE id=?", tenant);
        mvc.perform(get("/api/v1/me").cookie(session)).andExpect(status().isUnauthorized());
        assertEquals(Tokens.hash(session.getValue()),
                jdbc.queryForObject("SELECT token_hash FROM sessions WHERE user_id=? ORDER BY created_at DESC LIMIT 1",
                        String.class, admin));
    }

    @Test
    void tenantIdsInLoginAndUserRoutesCannotGrantOtherTenantAuthority() throws Exception {
        mvc.perform(post("/api/v1/auth/login").header("Origin", "http://localhost:5173").contentType("application/json")
                .content(json
                        .writeValueAsString(Map.of("email", email, "password", PASSWORD, "tenantId", foreignTenant))))
                .andExpect(status().isForbidden());
        String otherUser = createUser(foreignTenant, "MEMBER");
        Cookie session = login(email);
        mvc.perform(patch("/api/v1/org/users/" + otherUser + "/status").cookie(session)
                .header("Origin", "http://localhost:5173").contentType("application/json")
                .content("{\"status\":\"INACTIVE\"}")).andExpect(status().isNotFound());
        assertEquals("ACTIVE",
                jdbc.queryForObject("SELECT status FROM memberships WHERE user_id=?", String.class, otherUser));
    }

    @Test
    void lastAdministratorAndImmediateRoleChangeAreEnforced() throws Exception {
        Cookie session = login(email);
        mvc.perform(
                patch("/api/v1/org/users/" + admin + "/role").cookie(session).header("Origin", "http://localhost:5173")
                        .contentType("application/json").content("{\"role\":\"MEMBER\"}"))
                .andExpect(status().isConflict());
        createUser(tenant, "ADMIN");
        mvc.perform(
                patch("/api/v1/org/users/" + admin + "/role").cookie(session).header("Origin", "http://localhost:5173")
                        .contentType("application/json").content("{\"role\":\"MEMBER\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/org/invitations").cookie(session).header("Origin", "http://localhost:5173")
                .contentType("application/json").content("{\"email\":\"new@example.test\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void resetTokenIsSingleUseAndInvalidatesSessionsWithoutExposingAccountExistence() throws Exception {
        Cookie oldSession = login(email);
        String result = mvc
                .perform(post("/api/v1/auth/forgot-password").header("Origin", "http://localhost:5173")
                        .contentType("application/json").content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String absent = mvc
                .perform(post("/api/v1/auth/forgot-password").header("Origin", "http://localhost:5173")
                        .contentType("application/json").content("{\"email\":\"absent@example.test\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(result, absent);
        assertFalse(result.contains("token"));
        String body = jdbc.queryForObject(
                "SELECT body FROM mail_queue WHERE recipient=? ORDER BY created_at DESC LIMIT 1", String.class, email);
        String token = body.substring(body.indexOf("token=") + 6).split("\\s")[0];
        String payload = json.writeValueAsString(Map.of("token", token, "password", "NewDevelopmentPass!123"));
        mvc.perform(post("/api/v1/auth/reset-password").header("Origin", "http://localhost:5173")
                .contentType("application/json").content(payload)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/reset-password").header("Origin", "http://localhost:5173")
                .contentType("application/json").content(payload)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/me").cookie(oldSession)).andExpect(status().isUnauthorized());
    }

    @Test
    void invitationsAreHashedAndExistingIdentityCannotBeTakenOver() throws Exception {
        String existingUser = createUser(foreignTenant, "MEMBER");
        String recipient = jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, existingUser);
        Cookie session = login(email);
        String result = mvc
                .perform(post("/api/v1/org/invitations").cookie(session).header("Origin", "http://localhost:5173")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", recipient, "role", "MEMBER"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(result.contains("token"));
        String body = jdbc.queryForObject(
                "SELECT body FROM mail_queue WHERE recipient=? ORDER BY created_at DESC LIMIT 1", String.class,
                recipient);
        String token = body.substring(body.indexOf("token=") + 6).split("\\s")[0];
        assertEquals(Tokens.hash(token), jdbc.queryForObject(
                "SELECT token_hash FROM invitations WHERE tenant_id=? AND email=?", String.class, tenant, recipient));
        mvc.perform(post("/api/v1/invitations/accept").header("Origin", "http://localhost:5173")
                .contentType("application/json")
                .content(json.writeValueAsString(
                        Map.of("token", token, "name", "Attacker", "password", "AttackerNewPassword!"))))
                .andExpect(status().isForbidden());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM memberships WHERE tenant_id=? AND user_id=?",
                Integer.class, tenant, existingUser));
        mvc.perform(post("/api/v1/invitations/accept").header("Origin", "http://localhost:5173")
                .contentType("application/json").content(
                        json.writeValueAsString(Map.of("token", token, "name", "Existing user", "password", PASSWORD))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/invitations/accept").header("Origin", "http://localhost:5173")
                .contentType("application/json").content(
                        json.writeValueAsString(Map.of("token", token, "name", "Existing user", "password", PASSWORD))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void passwordChangeRequiresCurrentPasswordAndRevokesEveryDevice() throws Exception {
        Cookie first = login(email), second = login(email);
        mvc.perform(post("/api/v1/auth/change-password").cookie(first).header("Origin", "http://localhost:5173")
                .contentType("application/json")
                .content("{\"currentPassword\":\"wrong\",\"newPassword\":\"NewAccountPassword!123\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/change-password").cookie(first).header("Origin", "http://localhost:5173")
                .contentType("application/json")
                .content(json.writeValueAsString(
                        Map.of("currentPassword", PASSWORD, "newPassword", "NewAccountPassword!123"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").cookie(first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").cookie(second)).andExpect(status().isUnauthorized());
        assertTrue(encoder.matches("NewAccountPassword!123",
                jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, admin)));
    }

    @Test
    void sessionManagementNeverExposesSecretsOrRevokesAnotherUserSession() throws Exception {
        Cookie owner = login(email);
        String memberEmail = jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, member);
        Cookie other = login(memberEmail);
        String body = mvc.perform(get("/api/v1/me/sessions").cookie(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].current").value(true)).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("token"));
        assertFalse(body.contains(owner.getValue()));
        String otherId = jdbc.queryForObject("SELECT id FROM sessions WHERE token_hash=?", String.class,
                Tokens.hash(other.getValue()));
        mvc.perform(delete("/api/v1/me/sessions/" + otherId).cookie(owner).header("Origin", "http://localhost:5173"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me").cookie(other)).andExpect(status().isOk());
        String ownId = jdbc.queryForObject("SELECT id FROM sessions WHERE token_hash=?", String.class,
                Tokens.hash(owner.getValue()));
        mvc.perform(delete("/api/v1/me/sessions/" + ownId).cookie(owner).header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.current").value(true));
        mvc.perform(get("/api/v1/me").cookie(owner)).andExpect(status().isUnauthorized());
    }

    @Test
    void domainVerificationRequiresMatchingPublishedChallengeAndTenantOwnership() throws Exception {
        Cookie owner = login(email);
        String domain = "company-" + UUID.randomUUID() + ".example";
        String created = mvc
                .perform(post("/api/v1/org/domains").cookie(owner).header("Origin", "http://localhost:5173")
                        .contentType("application/json").content(json.writeValueAsString(Map.of("domain", domain))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).get("id").asText(),
                challenge = json.readTree(created).get("txtValue").asText();
        org.mockito.Mockito.when(dns.lookup("_enterpriseos." + domain))
                .thenReturn(java.util.List.of("wrong challenge"));
        mvc.perform(
                post("/api/v1/org/domains/" + id + "/verify").cookie(owner).header("Origin", "http://localhost:5173"))
                .andExpect(status().isConflict());
        org.mockito.Mockito.when(dns.lookup("_enterpriseos." + domain)).thenReturn(java.util.List.of(challenge));
        mvc.perform(
                post("/api/v1/org/domains/" + id + "/verify").cookie(owner).header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VERIFIED"));
        String other = createUser(foreignTenant, "ADMIN");
        Cookie foreign = login(jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, other));
        mvc.perform(
                post("/api/v1/org/domains/" + id + "/verify").cookie(foreign).header("Origin", "http://localhost:5173"))
                .andExpect(status().isNotFound());
    }

    @Test
    void oversizedBodiesAreRejectedBeforeAuthenticationWork() throws Exception {
        mvc.perform(post("/api/v1/auth/login").header("Origin", "http://localhost:5173").contentType("application/json")
                .content(new byte[8 * 1024 * 1024 + 1])).andExpect(status().is(413));
    }

    @Test
    void passwordResetCannotReuseHistoricalPasswords() throws Exception {
        Cookie owner = login(email);
        String changed = "ChangedPrivatePass!123";
        mvc.perform(post("/api/v1/auth/change-password").cookie(owner).header("Origin", "http://localhost:5173")
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("currentPassword", PASSWORD, "newPassword", changed))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/forgot-password").header("Origin", "http://localhost:5173")
                .contentType("application/json").content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk());
        String body = jdbc.queryForObject(
                "SELECT body FROM mail_queue WHERE recipient=? ORDER BY created_at DESC LIMIT 1", String.class, email);
        String token = body.substring(body.indexOf("token=") + 6).split("\\s")[0];
        mvc.perform(post("/api/v1/auth/reset-password").header("Origin", "http://localhost:5173")
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("token", token, "password", PASSWORD))))
                .andExpect(status().isBadRequest());
        assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(*) FROM password_history WHERE user_id=?", Integer.class, admin));
        mvc.perform(post("/api/v1/auth/reset-password").header("Origin", "http://localhost:5173")
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("token", token, "password", "UniqueNewPrivatePass!123"))))
                .andExpect(status().isOk());
    }

    @Test
    void failedLoginAuditSurvivesTransactionRollback() throws Exception {
        mvc.perform(post("/api/v1/auth/login").header("Origin", "http://localhost:5173").contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", email, "password", "IncorrectPassword!123"))))
                .andExpect(status().isUnauthorized());
        assertEquals(1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM authentication_attempts WHERE email_hash=? AND outcome='FAILED'",
                        Integer.class, Tokens.hash(email)));
    }

    @Test
    void publicCompanyRegistrationRequiresEmailProofAndCannotReplayVerification() throws Exception {
        String platform = createUser(foreignTenant, "ADMIN");
        jdbc.update("UPDATE users SET platform_role='PLATFORM_ADMIN' WHERE id=?", platform);
        String newEmail = UUID.randomUUID() + "@example.test", slug = "company-" + UUID.randomUUID();
        String payload = json.writeValueAsString(Map.of("companyName", "Verified company", "ownerName",
                "Verified owner", "email", newEmail, "password", PASSWORD, "slug", slug, "timezone", "Asia/Kolkata"));
        String response = mvc
                .perform(post("/api/v1/auth/register-company").header("Origin", "http://localhost:5173")
                        .contentType("application/json").content(payload))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("token"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email=?", Integer.class, newEmail));
        String message = jdbc.queryForObject(
                "SELECT body FROM mail_queue WHERE recipient=? ORDER BY created_at DESC LIMIT 1", String.class,
                newEmail);
        String token = message.substring(message.indexOf("token=") + 6).split("\\s")[0];
        String verify = json.writeValueAsString(Map.of("token", token));
        mvc.perform(post("/api/v1/auth/verify-company").header("Origin", "http://localhost:5173")
                .contentType("application/json").content(verify)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/verify-company").header("Origin", "http://localhost:5173")
                .contentType("application/json").content(verify)).andExpect(status().isBadRequest());
        Cookie session = login(newEmail);
        mvc.perform(get("/api/v1/me").cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void sessionRotationInvalidatesOldCookieAndPreservesAbsoluteExpiry() throws Exception {
        Cookie old = login(email);
        var response = mvc.perform(post("/api/v1/auth/refresh").cookie(old).header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String header = response.getHeader("Set-Cookie");
        Cookie rotated = new Cookie("EOS_SESSION", header.substring("EOS_SESSION=".length(), header.indexOf(';')));
        assertNotEquals(old.getValue(), rotated.getValue());
        mvc.perform(get("/api/v1/me").cookie(old)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").cookie(rotated)).andExpect(status().isOk());
        jdbc.update("UPDATE sessions SET authenticated_at=TIMESTAMPADD(HOUR,-25,UTC_TIMESTAMP(6)) WHERE token_hash=?",
                Tokens.hash(rotated.getValue()));
        mvc.perform(post("/api/v1/auth/refresh").cookie(rotated).header("Origin", "http://localhost:5173"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customRolesRestrictRoutesWithoutExceedingBaseAuthority() throws Exception {
        Cookie owner = login(email),
                staff = login(jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, member));
        String roleJson = json.writeValueAsString(Map.of("name", "Restricted worker", "baseRole", "MEMBER",
                "permissions", java.util.List.of("GET /api/v1/org/profile", "GET /api/v1/org/records/{kind}",
                        "POST /api/v1/org/records/{kind}")));
        String role = mvc
                .perform(post("/api/v1/org/custom-roles").cookie(owner).header("Origin", "http://localhost:5173")
                        .contentType("application/json").content(roleJson))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String roleId = json.readTree(role).get("id").asText(),
                assignment = json.writeValueAsString(Map.of("customRoleId", roleId));
        mvc.perform(put("/api/v1/org/users/" + member + "/custom-role").cookie(owner)
                .header("Origin", "http://localhost:5173").contentType("application/json").content(assignment))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/org/profile").cookie(staff)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/org/attendance").cookie(staff)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/org/records/departments").cookie(staff).header("Origin", "http://localhost:5173")
                .contentType("application/json").content("{\"name\":\"Escalation attempt\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/org/users/" + admin + "/custom-role").cookie(owner)
                .header("Origin", "http://localhost:5173").contentType("application/json").content(assignment))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/org/users/" + member + "/custom-role").cookie(owner)
                .header("Origin", "http://localhost:5173").contentType("application/json")
                .content("{\"customRoleId\":\"\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/org/attendance").cookie(staff)).andExpect(status().isOk());
    }

    @Test
    void bulkInvitationValidationIsAtomicAndRoleRestricted() throws Exception {
        Cookie owner = login(email);
        String duplicate = UUID.randomUUID() + "@example.test";
        String invalid = json.writeValueAsString(Map.of("rows", java.util.List
                .of(Map.of("email", duplicate, "role", "SALES"), Map.of("email", duplicate, "role", "MEMBER"))));
        mvc.perform(post("/api/v1/org/user-import/preview").cookie(owner).header("Origin", "http://localhost:5173")
                .contentType("application/json").content(invalid)).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
        mvc.perform(post("/api/v1/org/user-import").cookie(owner).header("Origin", "http://localhost:5173")
                .contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(*) FROM invitations WHERE tenant_id=?", Integer.class, tenant));
        String valid = json
                .writeValueAsString(Map.of("rows", java.util.List.of(Map.of("email", duplicate, "role", "SALES"),
                        Map.of("email", UUID.randomUUID() + "@example.test", "role", "PROCUREMENT"))));
        mvc.perform(post("/api/v1/org/user-import").cookie(owner).header("Origin", "http://localhost:5173")
                .contentType("application/json").content(valid)).andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(2));
        Cookie employee = login(jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, member));
        mvc.perform(post("/api/v1/org/user-import/preview").cookie(employee).header("Origin", "http://localhost:5173")
                .contentType("application/json").content(valid)).andExpect(status().isForbidden());
    }

    @Test
    void authenticatorSecretsAreEncryptedAndRecoveryCodesAreSingleUse() throws Exception {
        Cookie session = login(email);
        String setup = mvc
                .perform(post("/api/v1/me/mfa/setup").cookie(session).header("Origin", "http://localhost:5173")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("currentPassword", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String secret = json.readTree(setup).get("secret").asText();
        assertFalse(jdbc.queryForObject("SELECT pending_secret FROM user_mfa WHERE user_id=?", String.class, admin)
                .contains(secret));
        String otp = MfaService.totp(secret, java.time.Instant.now().getEpochSecond() / 30);
        String activated = mvc
                .perform(post("/api/v1/me/mfa/enable").cookie(session).header("Origin", "http://localhost:5173")
                        .contentType("application/json").content(json.writeValueAsString(Map.of("otp", otp))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String recovery = json.readTree(activated).get("recoveryCodes").get(0).asText();
        mvc.perform(post("/api/v1/auth/login").header("Origin", "http://localhost:5173").contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isUnauthorized());
        String payload = json.writeValueAsString(Map.of("email", email, "password", PASSWORD, "otp", recovery));
        mvc.perform(post("/api/v1/auth/login").header("Origin", "http://localhost:5173").contentType("application/json")
                .content(payload)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/login").header("Origin", "http://localhost:5173").contentType("application/json")
                .content(payload)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/mfa").cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(*) FROM mfa_recovery_codes WHERE user_id=? AND used_at IS NOT NULL",
                        Integer.class, admin));
    }

    @Test
    void logoUploadValidatesContentAndStorageRemainsTenantScoped() throws Exception {
        Cookie owner = login(email);
        var output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),
                "png", output);
        byte[] image = output.toByteArray();
        String data = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(image);
        mvc.perform(post("/api/v1/org/logo").cookie(owner).header("Origin", "http://localhost:5173")
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("dataUrl", data, "tenantId", foreignTenant))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.logoKey").value("/api/v1/org/logo"));
        mvc.perform(get("/api/v1/org/logo").cookie(owner)).andExpect(status().isOk())
                .andExpect(content().contentType("image/png")).andExpect(content().bytes(image));
        String stored = jdbc.queryForObject("SELECT logo_key FROM organization_profiles WHERE tenant_id=?",
                String.class, tenant);
        assertNotNull(stored);
        assertTrue(stored.startsWith(tenant + "-"));
        mvc.perform(get("/api/v1/org/profile").cookie(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.logoKey").value("/api/v1/org/logo"));
        mvc.perform(put("/api/v1/org/profile").cookie(owner).header("Origin", "http://localhost:5173")
                .contentType("application/json")
                .content(json.writeValueAsString(
                        Map.of("name", "Updated", "email", email, "timezone", "UTC", "logoKey", "/api/v1/org/logo"))))
                .andExpect(status().isOk());
        assertEquals(stored, jdbc.queryForObject("SELECT logo_key FROM organization_profiles WHERE tenant_id=?",
                String.class, tenant));
        mvc.perform(put("/api/v1/org/profile").cookie(owner).header("Origin", "http://localhost:5173")
                .contentType("application/json")
                .content(json.writeValueAsString(
                        Map.of("name", "Updated", "email", email, "logoKey", "https://other.example/tracker.png"))))
                .andExpect(status().isBadRequest());
        String foreignUser = createUser(foreignTenant, "ADMIN");
        Cookie foreign = login(jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, foreignUser));
        mvc.perform(get("/api/v1/org/logo?tenantId=" + tenant).cookie(foreign)).andExpect(status().isNotFound());
        for (String invalid : java.util.List.of(
                "data:image/svg+xml;base64," + java.util.Base64.getEncoder().encodeToString("<svg/>".getBytes()),
                "data:image/png;base64,"
                        + java.util.Base64.getEncoder().encodeToString("<html>not an image</html>".getBytes())))
            mvc.perform(post("/api/v1/org/logo").cookie(owner).header("Origin", "http://localhost:5173")
                    .contentType("application/json").content(json.writeValueAsString(Map.of("dataUrl", invalid))))
                    .andExpect(status().isBadRequest());
        String oversized = "data:image/png;base64,"
                + java.util.Base64.getEncoder().encodeToString(new byte[512 * 1024 + 1]);
        mvc.perform(post("/api/v1/org/logo").cookie(owner).header("Origin", "http://localhost:5173")
                .contentType("application/json").content(json.writeValueAsString(Map.of("dataUrl", oversized))))
                .andExpect(status().isBadRequest());
        Cookie ordinary = login(jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, member));
        mvc.perform(post("/api/v1/org/logo").cookie(ordinary).header("Origin", "http://localhost:5173")
                .contentType("application/json").content(json.writeValueAsString(Map.of("dataUrl", data))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/org/logo").cookie(owner)).andExpect(status().isOk()).andExpect(content().bytes(image));
    }
}
