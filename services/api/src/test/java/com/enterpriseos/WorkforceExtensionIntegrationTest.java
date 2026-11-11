package com.enterpriseos;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = { "app.seed-demo=false", "app.workforce-jobs-enabled=false",
        "app.business-reminders-enabled=false" })
@AutoConfigureMockMvc
@EnabledIf("com.enterpriseos.TestDatabase#available")
class WorkforceExtensionIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        TestDatabase.configure(r);
    }

    @Autowired
    WorkforceExtensionController extension;
    @Autowired
    OperationsController operations;
    @Autowired
    JdbcTemplate db;
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    BCryptPasswordEncoder encoder;
    String tenant;
    Principal admin, hr, member, stranger;

    String id() {
        return UUID.randomUUID().toString();
    }

    String rid(Map<String, Object> r) {
        return r.get("id").toString();
    }

    @BeforeEach
    void seed() {
        tenant = id();
        db.update(
                "INSERT INTO tenants(id,name,slug,status,timezone,created_at) VALUES(?,?,?,'ACTIVE','Asia/Kolkata',UTC_TIMESTAMP(6))",
                tenant, "SolDrashta", tenant);
        admin = user(tenant, "ADMIN");
        hr = user(tenant, "HR");
        member = user(tenant, "MEMBER");
        stranger = user(tenant, "MEMBER");
    }

    Principal user(String tenant, String role) {
        String user = id();
        db.update(
                "INSERT INTO users(id,name,email,password_hash,status,created_at) VALUES(?,?,?,?,'ACTIVE',UTC_TIMESTAMP(6))",
                user, role, user + "@test.invalid", "unused");
        db.update("INSERT INTO memberships(id,tenant_id,user_id,role,status) VALUES(?,?,?,?,'ACTIVE')", id(), tenant,
                user, role);
        return new Principal(user, tenant, role, user + "@test.invalid", role);
    }

    Map<String, Object> employee(Principal p) {
        return operations.create(admin, "employees", Map.of("name", p.name(), "employeeCode",
                p.userId().substring(0, 8), "userId", p.userId(), "joinDate", "2020-01-01"));
    }

    void leave(String name, int days) {
        operations.create(admin, "leavePolicies", Map.of("name", name, "type", name, "annualDays", days));
    }

    LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata"));
    }

    @Test
    void ownExitNeedsIndependentHrAndReusesOffboardingSessionGuards() {
        Map<String, Object> employee = employee(member);
        String session = id();
        db.update(
                "INSERT INTO sessions(id,token_hash,user_id,tenant_id,created_at,expires_at) VALUES(?,?,?,?,UTC_TIMESTAMP(6),TIMESTAMPADD(DAY,1,UTC_TIMESTAMP(6)))",
                session, id() + "1234567890123456789012345678", member.userId(), tenant);
        Map<String, Object> request = extension.submit(member, "exits",
                Map.of("exitDate", today().toString(), "reason", "Resignation"));
        assertEquals(403, assertThrows(ApiException.class,
                () -> extension.decide(member, rid(request), Map.of("decision", "APPROVED"))).status());
        assertEquals("COMPLETED", extension.decide(hr, rid(request), Map.of("decision", "APPROVED")).get("status"));
        assertEquals("INACTIVE", db.queryForObject("SELECT status FROM memberships WHERE tenant_id=? AND user_id=?",
                String.class, tenant, member.userId()));
        assertNotNull(
                db.queryForObject("SELECT revoked_at FROM sessions WHERE id=?", java.sql.Timestamp.class, session));
        assertEquals("OFFBOARDED", operations.list(hr, "employees").stream()
                .filter(e -> rid(employee).equals(e.get("id"))).findFirst().orElseThrow().get("status"));
    }

    @Test
    void futureExitApprovalDoesNotRevokeAccessBeforeEffectiveDate() {
        employee(member);
        Map<String, Object> request = extension.submit(member, "exits",
                Map.of("exitDate", today().plusDays(10).toString(), "reason", "Notice period"));
        assertEquals("APPROVED", extension.decide(hr, rid(request), Map.of("decision", "APPROVED")).get("status"));
        assertEquals("ACTIVE", db.queryForObject("SELECT status FROM memberships WHERE tenant_id=? AND user_id=?",
                String.class, tenant, member.userId()));
        assertEquals(409, assertThrows(ApiException.class, () -> extension.completeExit(hr, rid(request))).status());
        assertEquals(409, assertThrows(ApiException.class, () -> extension.submit(member, "exits",
                Map.of("exitDate", today().plusDays(11).toString(), "reason", "Duplicate"))).status());
    }

    @Test
    void encashmentIsEligibilityOnlyAndProtectsMinimumLeaveBalance() {
        employee(member);
        leave("Annual", 20);
        extension.createPolicy(hr, "encashment", Map.of("name", "Encashment", "leaveType", "Annual",
                "eligibleAfterMonths", 6, "minRemainingDays", 5, "maxDays", 10));
        assertEquals(409,
                assertThrows(ApiException.class,
                        () -> extension.submit(member, "encashments", Map.of("days", 11, "reason", "Too much")))
                        .status());
        Map<String, Object> request = extension.submit(member, "encashments",
                Map.of("days", 10, "reason", "Eligibility request"));
        Map<String, Object> result = extension.decide(hr, rid(request), Map.of("decision", "APPROVED"));
        assertEquals("ELIGIBLE", result.get("status"));
        assertEquals(Boolean.TRUE, result.get("eligibilityOnly"));
        assertEquals(20, ((BigDecimal) operations.balances(member).getFirst().get("remaining")).intValueExact());
        assertEquals(0,
                db.queryForObject("SELECT COUNT(*) FROM leave_adjustments WHERE tenant_id=?", Integer.class, tenant));
    }

    @Test
    void compOffUsesActualHoursAndConcurrentApprovalCreditsOnce() throws Exception {
        employee(member);
        leave("CompOff", 0);
        extension.createPolicy(hr, "compOff",
                Map.of("name", "Weekend work", "leaveType", "CompOff", "hoursPerDay", 8, "maxDays", 5));
        LocalDate worked = today().with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        Instant start = worked.atTime(9, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
        db.update("INSERT INTO attendance(id,tenant_id,user_id,in_at,out_at,work_date) VALUES(?,?,?,?,?,?)", id(),
                tenant, member.userId(), start.toString(), start.plus(Duration.ofHours(8)).toString(), worked);
        assertEquals(409,
                assertThrows(ApiException.class,
                        () -> extension.submit(member, "compOff",
                                Map.of("workDate", worked.toString(), "hours", 9, "reason", "Exceeds evidence")))
                        .status());
        Map<String, Object> request = extension.submit(member, "compOff",
                Map.of("workDate", worked.toString(), "hours", 8, "reason", "Weekend support"));
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(1);
            Callable<Integer> approve = () -> {
                ready.await();
                try {
                    extension.decide(hr, rid(request), Map.of("decision", "APPROVED"));
                    return 200;
                } catch (ApiException e) {
                    return e.status();
                }
            };
            Future<Integer> first = executor.submit(approve), second = executor.submit(approve);
            ready.countDown();
            List<Integer> statuses = new ArrayList<>(
                    List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)));
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
        }
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM workforce_credit_postings WHERE tenant_id=?",
                Integer.class, tenant));
        assertEquals(1, ((BigDecimal) operations.balances(member).getFirst().get("remaining")).intValueExact());
    }

    @Test
    void riskRegisterIsProjectScopedVersionedAndReadOnlyForMembers() {
        Map<String, Object> project = operations.create(admin, "projects",
                Map.of("name", "Delivery", "memberIds", List.of(member.userId())));
        Map<String, Object> risk = extension.createRisk(admin, rid(project), Map.of("name", "Dependency", "probability",
                3, "impact", 4, "ownerId", member.userId(), "mitigation", "Contingency"));
        assertEquals(12, risk.get("score"));
        assertEquals(1, extension.risks(member, rid(project)).size());
        assertEquals(404, assertThrows(ApiException.class, () -> extension.risks(stranger, rid(project))).status());
        assertEquals(403,
                assertThrows(ApiException.class,
                        () -> extension.updateRisk(member, rid(risk), Map.of("version", 1, "status", "CLOSED")))
                        .status());
        extension.updateRisk(admin, rid(risk), Map.of("version", 1, "status", "MITIGATING"));
        assertEquals(409,
                assertThrows(ApiException.class,
                        () -> extension.updateRisk(admin, rid(risk), Map.of("version", 1, "status", "CLOSED")))
                        .status());
    }

    @Test
    void concurrentRecurringRunsGenerateOneOccurrenceAndArchivedProjectsStopWork() throws Exception {
        Map<String, Object> project = operations.create(admin, "projects",
                Map.of("name", "Delivery", "memberIds", List.of(member.userId())));
        Map<String, Object> template = extension.createRecurring(admin,
                Map.of("name", "Weekly report", "projectId", rid(project), "assigneeId", member.userId(),
                        "intervalDays", 7, "nextDate", today().toString(), "endDate", today().toString()));
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(1);
            Callable<Integer> run = () -> {
                ready.await();
                return ((Number) extension.runDue(admin).get("generated")).intValue();
            };
            Future<Integer> a = executor.submit(run), b = executor.submit(run);
            ready.countDown();
            assertEquals(1, a.get(20, TimeUnit.SECONDS) + b.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, extension.occurrences(member, rid(template)).size());
        assertEquals(1, operations.list(member, "tasks").size());
        assertEquals(403, assertThrows(ApiException.class, () -> extension.runDue(member)).status());
        Map<String, Object> another = extension.createRecurring(admin,
                Map.of("name", "Future", "projectId", rid(project), "intervalDays", 1, "nextDate", today().toString()));
        operations.update(admin, "projects", rid(project), Map.of("version", 1, "status", "ARCHIVED"));
        assertEquals(0, ((Number) extension.runDue(admin).get("generated")).intValue());
        assertNotNull(extension.recurringTasks(admin).stream().filter(r -> rid(another).equals(r.get("id"))).findFirst()
                .orElseThrow().get("lastError"));
    }

    @Test
    void analyticsUsesActualHistoryAndCannotRevealOtherProjects() {
        Map<String, Object> project = operations.create(admin, "projects",
                Map.of("name", "Shared", "memberIds", List.of(member.userId())));
        Map<String, Object> task = operations.create(admin, "tasks", Map.of("name", "Overdue", "projectId",
                rid(project), "assigneeId", member.userId(), "dueDate", today().minusDays(1).toString()));
        Map<String, Object> report = extension.taskAnalytics(member);
        assertEquals(1, report.get("total"));
        assertEquals(1, report.get("overdue"));
        assertEquals(0, extension.taskAnalytics(stranger).get("total"));
        operations.update(member, "tasks", rid(task), Map.of("version", 1, "status", "DONE"));
        Map<String, Object> completed = extension.taskAnalytics(member);
        assertEquals(0, completed.get("overdue"));
        assertFalse(((Map<?, ?>) completed.get("weeklyThroughput")).isEmpty());
    }

    @Test
    void tenantPolicyAndRequestIdentifiersCannotBeReusedAcrossCompanies() {
        employee(member);
        Map<String, Object> request = extension.submit(member, "exits",
                Map.of("exitDate", today().plusDays(1).toString(), "reason", "Notice"));
        String foreign = id();
        db.update(
                "INSERT INTO tenants(id,name,slug,status,timezone,created_at) VALUES(?,?,?,'ACTIVE','UTC',UTC_TIMESTAMP(6))",
                foreign, "Other", foreign);
        Principal foreignHr = user(foreign, "HR");
        assertEquals(404, assertThrows(ApiException.class,
                () -> extension.decide(foreignHr, rid(request), Map.of("decision", "APPROVED"))).status());
        assertTrue(extension.requests(foreignHr, "exits").isEmpty());
    }

    @Test
    void analyticsActionCannotBypassDeniedUnderlyingTaskPermission() {
        Map<String, Object> project = operations.create(admin, "projects",
                Map.of("name", "Visible", "memberIds", List.of(member.userId())));
        operations.create(admin, "tasks",
                Map.of("name", "Private source", "projectId", rid(project), "assigneeId", member.userId()));
        String roleId = id();
        db.update("INSERT INTO custom_roles(id,tenant_id,name,base_role,permissions) VALUES(?,?,?,'MEMBER',?)", roleId,
                tenant, "Analytics without source", "[\"GET /api/v1/org/workforce/analytics/tasks\"]");
        db.update("UPDATE memberships SET custom_role_id=? WHERE tenant_id=? AND user_id=?", roleId, tenant,
                member.userId());
        assertEquals(403, assertThrows(ApiException.class, () -> extension.taskAnalytics(member)).status());
    }

    @Test
    void httpRecurringBatchCommitsValidTemplateAndReportsArchivedTemplateWithoutRollback() throws Exception {
        Map<String, Object> validProject = operations.create(admin, "projects",
                Map.of("name", "Active project", "memberIds", List.of(member.userId()))),
                archived = operations.create(admin, "projects", Map.of("name", "Archived project"));
        Map<String, Object> good = extension.createRecurring(admin,
                Map.of("name", "Valid occurrence", "projectId", rid(validProject), "assigneeId", member.userId(),
                        "intervalDays", 7, "nextDate", today().toString(), "endDate", today().toString()));
        Map<String, Object> bad = extension.createRecurring(admin, Map.of("name", "Blocked occurrence", "projectId",
                rid(archived), "intervalDays", 7, "nextDate", today().toString()));
        operations.update(admin, "projects", rid(archived), Map.of("version", 1, "status", "ARCHIVED"));
        String password = "PrivateWorkforceHttp!123";
        db.update("UPDATE users SET password_hash=? WHERE id=?", encoder.encode(password), admin.userId());
        String header = mvc
                .perform(post("/api/v1/auth/login").header("Origin", "http://localhost:5173")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", admin.email(), "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("Set-Cookie");
        Cookie session = new Cookie("EOS_SESSION", header.substring("EOS_SESSION=".length(), header.indexOf(';')));
        String response = mvc
                .perform(post("/api/v1/org/workforce/recurring-tasks/run-due").cookie(session).header("Origin",
                        "http://localhost:5173"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.generated").value(1)).andReturn().getResponse()
                .getContentAsString();
        assertTrue(response.contains("Archived projects are read-only"));
        assertEquals(1, extension.occurrences(admin, rid(good)).size());
        assertTrue(extension.occurrences(admin, rid(bad)).isEmpty());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM business_records WHERE tenant_id=? AND kind='tasks'",
                Integer.class, tenant));
        assertNotNull(extension.recurringTasks(admin).stream().filter(r -> rid(bad).equals(r.get("id"))).findFirst()
                .orElseThrow().get("lastError"));
    }
}
