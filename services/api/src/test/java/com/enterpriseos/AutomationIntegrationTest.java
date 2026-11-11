package com.enterpriseos;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = { "APP_ENCRYPTION_KEY=1111111111111111111111111111111111111111111111111111111111111111",
        "APP_DOCUMENT_DIR=./target/test-documents", "APP_WEBHOOK_POLL_MS=3600000", "APP_WORKFLOW_POLL_MS=3600000",
        "APP_REPORT_POLL_MS=3600000" })
@AutoConfigureMockMvc
@EnabledIf("com.enterpriseos.TestDatabase#available")
class AutomationIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        TestDatabase.configure(r);
    }

    @Autowired
    AutomationController automation;
    @Autowired
    DocumentController documents;
    @Autowired
    PlatformController platform;
    @Autowired
    IntegrationController integrations;
    @Autowired
    AutomationJobs jobs;
    @Autowired
    ReportService reports;
    @Autowired
    OperationsController workforce;
    @Autowired
    EntitlementService entitlements;
    @Autowired
    JdbcTemplate db;
    @Autowired
    ObjectMapper json;
    @Autowired
    MockMvc mvc;
    Principal admin, member, hr, manager, foreignAdmin, platformAdmin;
    String tenant;

    private String id() {
        return UUID.randomUUID().toString();
    }

    @BeforeEach
    void seed() {
        tenant = id();
        String foreign = id();
        for (String t : List.of(tenant, foreign)) {
            db.update(
                    "INSERT INTO tenants(id,name,slug,status,timezone,created_at) VALUES(?,?,?,'ACTIVE','UTC',UTC_TIMESTAMP(6))",
                    t, "Automation Test", t);
            db.update(
                    "INSERT INTO organization_profiles(tenant_id,legal_name,email) VALUES (?,'Test','test@example.test')",
                    t);
        }
        admin = user(tenant, "ADMIN");
        member = user(tenant, "MEMBER");
        hr = user(tenant, "HR");
        manager = user(tenant, "MANAGER");
        foreignAdmin = user(foreign, "ADMIN");
        String p = id();
        db.update(
                "INSERT INTO users(id,name,email,password_hash,status,platform_role,created_at) VALUES(?,?,?,?,'ACTIVE','PLATFORM_ADMIN',UTC_TIMESTAMP(6))",
                p, "Platform", p + "@example.test", "unused");
        platformAdmin = new Principal(p, null, "Platform", p + "@example.test", "PLATFORM_ADMIN");
    }

    private Principal user(String t, String role) {
        String id = id();
        db.update(
                "INSERT INTO users(id,name,email,password_hash,status,created_at) VALUES(?,?,?,?,'ACTIVE',UTC_TIMESTAMP(6))",
                id, role, id + "@example.test", "unused");
        db.update("INSERT INTO memberships(id,tenant_id,user_id,role,status) VALUES(?,?,?,?,'ACTIVE')", id(), t, id,
                role);
        return new Principal(id, t, role, id + "@example.test", role);
    }

    private Map<String, Object> step(String name, String role, String mode) {
        return Map.of("name", name, "approverRole", role, "mode", mode, "dueHours", 1);
    }

    private String definition(List<Map<String, Object>> steps) {
        String id = automation.createDefinition(admin, Map.of("name", "Process " + id(), "steps", steps)).get("id")
                .toString();
        automation.publish(admin, id);
        return id;
    }

    private String instance(String definition) {
        return automation
                .start(member, Map.of("definitionId", definition, "title", "Independent approval", "amount", 100))
                .get("id").toString();
    }

    @Test
    void publishedSnapshotsStayImmutableAndParallelGroupRequiresEveryApprover() {
        Principal otherHr = user(tenant, "HR");
        String def = definition(List.of(step("HR review", "HR", "ALL")));
        String instance = instance(def);
        assertEquals(403, assertThrows(ApiException.class,
                () -> automation.decide(member, instance, Map.of("decision", "APPROVED"))).status());
        var current = automation.definitions(admin).stream().filter(d -> def.equals(d.get("id"))).findFirst()
                .orElseThrow();
        automation.editDefinition(admin, def, Map.of("name", current.get("name"), "version", current.get("version"),
                "steps", List.of(step("Manager review", "MANAGER", "ANY"))));
        automation.publish(admin, def);
        assertEquals(1, ((Number) automation.instances(member).getFirst().get("definitionVersion")).intValue());
        assertEquals("PENDING", automation.decide(hr, instance, Map.of("decision", "APPROVED")).get("state"));
        assertEquals("APPROVED", automation.decide(otherHr, instance, Map.of("decision", "APPROVED")).get("state"));
        assertEquals(409, assertThrows(ApiException.class,
                () -> automation.decide(otherHr, instance, Map.of("decision", "APPROVED"))).status());
        assertEquals(404, assertThrows(ApiException.class, () -> automation.history(foreignAdmin, instance)).status());
    }

    @Test
    void concurrentDecisionsProduceOneTransition() throws Exception {
        String instance = instance(definition(List.of(step("Review", "HR", "ANY"))));
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch gate = new CountDownLatch(1);
            Callable<Integer> action = () -> {
                gate.await();
                try {
                    automation.decide(hr, instance, Map.of("decision", "APPROVED"));
                    return 200;
                } catch (ApiException ex) {
                    return ex.status();
                }
            };
            Future<Integer> a = pool.submit(action), b = pool.submit(action);
            gate.countDown();
            var statuses = new ArrayList<>(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)));
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
        }
        assertEquals(1,
                db.queryForObject("SELECT COUNT(*) FROM workflow_actions WHERE instance_id=? AND action='APPROVED'",
                        Integer.class, instance));
    }

    @Test
    void escalationOnlyOccursAfterDeadlineAndNeverSelfApproves() {
        String instance = instance(definition(List.of(step("Review", "HR", "ANY"))));
        jobs.escalate();
        assertEquals(403, assertThrows(ApiException.class,
                () -> automation.decide(admin, instance, Map.of("decision", "APPROVED"))).status());
        db.update("UPDATE workflow_instances SET due_at=TIMESTAMPADD(HOUR,-1,UTC_TIMESTAMP(6)) WHERE id=?", instance);
        jobs.escalate();
        assertEquals("PENDING",
                db.queryForObject("SELECT state FROM workflow_instances WHERE id=?", String.class, instance));
        assertEquals("APPROVED", automation.decide(admin, instance, Map.of("decision", "APPROVED")).get("state"));
        assertEquals(1,
                db.queryForObject("SELECT COUNT(*) FROM workflow_actions WHERE instance_id=? AND action='ESCALATED'",
                        Integer.class, instance));
    }

    @Test
    void notificationPreferencesAndReadStateAreUserScoped() {
        workforce.create(admin, "departments", Map.of("name", "One"));
        String notification = automation.notifications(admin).getFirst().get("id").toString();
        assertEquals(404, assertThrows(ApiException.class, () -> automation.read(member, notification)).status());
        automation.read(admin, notification);
        automation.preferences(admin, Map.of("eventType", "*", "inApp", false, "email", false));
        int before = automation.notifications(admin).size();
        workforce.create(admin, "departments", Map.of("name", "Two"));
        assertEquals(before, automation.notifications(admin).size());
        assertTrue(automation.deliveries(admin).stream().anyMatch(d -> d.get("status").equals("SUPPRESSED")));
        assertTrue(automation.notifications(foreignAdmin).isEmpty());
    }

    @Test
    void documentsAreQuarantinedVersionedAndPrivateUntilAuthorizedReview() {
        String data = "data:text/plain;base64,"
                + Base64.getEncoder().encodeToString("Private employee document".getBytes(StandardCharsets.UTF_8));
        var doc = documents.upload(member,
                Map.of("name", "profile.txt", "category", "HR", "visibility", "PRIVATE", "dataUrl", data));
        String id = doc.get("id").toString();
        assertEquals("PENDING_REVIEW", doc.get("scanStatus"));
        assertEquals(409, assertThrows(ApiException.class, () -> documents.download(member, id, null, null)).status());
        assertEquals(403, assertThrows(ApiException.class, () -> documents.download(hr, id, null, null)).status());
        assertEquals(404,
                assertThrows(ApiException.class, () -> documents.download(foreignAdmin, id, null, null)).status());
        documents.review(admin, id,
                Map.of("decision", "CLEAN", "evidence", "Independent review of plain UTF-8 content"));
        assertArrayEquals("Private employee document".getBytes(StandardCharsets.UTF_8),
                documents.download(member, id, null, null).getBody());
        documents.version(member, id, Map.of("dataUrl", data));
        assertEquals(2, documents.versions(member, id).size());
        assertEquals(409, assertThrows(ApiException.class, () -> documents.download(member, id, null, null)).status());
        assertNotNull(documents.download(member, id, 1, null).getBody());
        documents.retention(admin, id,
                Map.of("retentionUntil", LocalDate.now().plusDays(30).toString(), "legalHold", true));
        assertEquals(409, assertThrows(ApiException.class, () -> documents.delete(admin, id)).status());
        assertEquals(400, assertThrows(ApiException.class, () -> documents.upload(member,
                Map.of("name", "bad.svg", "visibility", "PRIVATE", "dataUrl", "data:image/svg+xml;base64,PHN2Zy8+")))
                .status());
    }

    @Test
    void reportsReconcileAndSnapshotsRecheckCurrentObjectAccess() {
        var project = workforce.create(admin, "projects",
                Map.of("name", "Visible project", "ownerId", admin.userId(), "memberIds", List.of(member.userId())));
        var snapshot = reports.generate(member, "My project snapshot", "projects", Map.of(), null);
        assertEquals(1, ((Number) snapshot.get("rowCount")).intValue());
        assertTrue(new String(reports.content(member, snapshot.get("id").toString()), StandardCharsets.UTF_8)
                .contains("Visible project"));
        workforce.update(admin, "projects", project.get("id").toString(),
                Map.of("version", project.get("version"), "memberIds", List.of()));
        assertEquals(403, assertThrows(ApiException.class, () -> reports.content(member, snapshot.get("id").toString()))
                .status());
        assertEquals(403,
                assertThrows(ApiException.class, () -> reports.generate(member, "Payroll", "payroll", Map.of(), null))
                        .status());
        workforce.create(admin, "employees", Map.of("name", "=Dangerous formula", "employeeCode", "R1"));
        var employees = reports.generate(admin, "Employee export", "employees", Map.of(), null);
        String csv = new String(reports.content(admin, employees.get("id").toString()), StandardCharsets.UTF_8);
        assertTrue(csv.contains("'=Dangerous formula"));
        assertEquals(1, ((Number) employees.get("rowCount")).intValue());
    }

    @Test
    void subscriptionsEnforceModuleRecordAndSeatLimitsWithoutDeletingData() {
        var plan = platform.plan(platformAdmin,
                Map.of("name", "Limited " + id(), "seatLimit", 4, "recordLimit", 1, "modules", List.of("HR")));
        var sub = platform.subscribe(platformAdmin, Map.of("tenantId", tenant, "planId", plan.get("id"), "status",
                "ACTIVE", "expiresAt", Instant.now().plusSeconds(3600).toString()));
        assertEquals(409, assertThrows(ApiException.class, () -> entitlements.checkSeat(tenant)).status());
        var department = workforce.create(admin, "departments", Map.of("name", "Within quota"));
        assertEquals(409, assertThrows(ApiException.class,
                () -> workforce.create(admin, "departments", Map.of("name", "Over quota"))).status());
        assertEquals(403, assertThrows(ApiException.class,
                () -> workforce.create(admin, "projects", Map.of("name", "Not entitled"))).status());
        platform.change(platformAdmin, sub.get("id").toString(), Map.of("version", sub.get("version"), "planId",
                plan.get("id"), "status", "SUSPENDED", "expiresAt", Instant.now().plusSeconds(3600).toString()));
        assertEquals(403, assertThrows(ApiException.class, () -> workforce.update(admin, "departments",
                department.get("id").toString(), Map.of("name", "Blocked write", "version", 1))).status());
        assertEquals(1, workforce.list(admin, "departments").size());
    }

    @Test
    void signedInboundWebhooksRejectTamperingExpiryAndReplay() throws Exception {
        var endpoint = integrations.endpoint(admin, Map.of("name", "Inbound events", "direction", "INBOUND"));
        String id = endpoint.get("id").toString(), event = id(),
                timestamp = Long.toString(Instant.now().getEpochSecond());
        byte[] body = json.writeValueAsBytes(Map.of("id", event, "type", "PING", "entity", "test", "entityId", id()));
        String signature = "sha256="
                + WebhookDeliveryService.signature(endpoint.get("secret").toString(), timestamp, body);
        mvc.perform(post("/api/v1/integrations/webhooks/" + id).header("X-EOS-Timestamp", timestamp)
                .header("X-EOS-Event-Id", event).header("X-EOS-Signature", signature).contentType("application/json")
                .content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/integrations/webhooks/" + id).header("X-EOS-Timestamp", timestamp)
                .header("X-EOS-Event-Id", event).header("X-EOS-Signature", signature).contentType("application/json")
                .content(body)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/integrations/webhooks/" + id).header("X-EOS-Timestamp", timestamp)
                .header("X-EOS-Event-Id", event).header("X-EOS-Signature", signature).contentType("application/json")
                .content(body)).andExpect(status().isConflict());
        String expired = Long.toString(Instant.now().minusSeconds(600).getEpochSecond());
        assertEquals(403,
                assertThrows(ApiException.class, () -> integrations.receive(id, expired, id(), signature, body))
                        .status());
        assertEquals(1, integrations.inbound(admin).size());
        assertTrue(integrations.inbound(foreignAdmin).isEmpty());
    }

    @Test
    void scopedCredentialsNeverExposePrivateFieldsAndRevocationIsImmediate() throws Exception {
        workforce.create(admin, "employees",
                Map.of("name", "Employee", "employeeCode", "E1", "salary", 100000, "nationalId", "SECRET"));
        var credential = integrations.credential(admin,
                Map.of("name", "HR reader", "scopes", List.of("employees:read")));
        String token = credential.get("token").toString();
        String body = mvc.perform(get("/api/v1/integrations/data/employees").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("SECRET"));
        assertFalse(body.contains("salary"));
        mvc.perform(get("/api/v1/integrations/data/invoices").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        integrations.revoke(admin, credential.get("id").toString());
        mvc.perform(get("/api/v1/integrations/data/employees").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
