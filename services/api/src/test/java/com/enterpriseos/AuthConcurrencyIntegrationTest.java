package com.enterpriseos;

import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = { "app.seed-demo=false", "app.workforce-jobs-enabled=false",
        "app.business-reminders-enabled=false", "spring.datasource.hikari.maximum-pool-size=10" })
@AutoConfigureMockMvc
@EnabledIf("com.enterpriseos.TestDatabase#available")
class AuthConcurrencyIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.configure(registry);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate db;
    @Autowired
    ObjectMapper json;
    @Autowired
    PlatformTransactionManager manager;
    @SpyBean
    BCryptPasswordEncoder encoder;
    String tenant, owner, member, email;
    private static final String PASSWORD = "ConcurrencyPrivatePass!123";

    @BeforeEach
    void seed() {
        tenant = UUID.randomUUID().toString();
        db.update(
                "INSERT INTO tenants(id,name,slug,status,timezone,created_at) VALUES(?, 'Concurrency',?,'ACTIVE','UTC',UTC_TIMESTAMP(6))",
                tenant, tenant);
        owner = user("ADMIN");
        member = user("MEMBER");
        email = email(owner);
    }

    String user(String role) {
        String id = UUID.randomUUID().toString();
        db.update(
                "INSERT INTO users(id,name,email,password_hash,status,created_at) VALUES(?,?,?,?, 'ACTIVE',UTC_TIMESTAMP(6))",
                id, role, id + "@test.invalid", encoder.encode(PASSWORD));
        db.update("INSERT INTO memberships(id,tenant_id,user_id,role,status) VALUES(?,?,?,?,'ACTIVE')",
                UUID.randomUUID().toString(), tenant, id, role);
        return id;
    }

    String email(String user) {
        return db.queryForObject("SELECT email FROM users WHERE id=?", String.class, user);
    }

    Cookie cookie(String account) throws Exception {
        String header = mvc
                .perform(post("/api/v1/auth/login").header("Origin", "http://localhost:5173")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", account, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("Set-Cookie");
        return new Cookie("EOS_SESSION", header.substring("EOS_SESSION=".length(), header.indexOf(';')));
    }

    @Test
    void concurrentResetCannotLeaveALoginAuthenticatedWithThePreviousPassword() throws Exception {
        String stored = db.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, owner),
                newPassword = "NewConcurrencyPrivate!123", raw = Tokens.issue();
        db.update(
                "INSERT INTO password_reset_tokens(id,user_id,token_hash,expires_at,created_at) VALUES(?,?,?,TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)),UTC_TIMESTAMP(6))",
                UUID.randomUUID().toString(), owner, Tokens.hash(raw));
        CountDownLatch loginChecking = new CountDownLatch(1), releaseLogin = new CountDownLatch(1),
                resetHasUserLock = new CountDownLatch(1);
        doAnswer(call -> {
            String password = call.getArgument(0).toString();
            if (password.equals(PASSWORD)) {
                loginChecking.countDown();
                assertTrue(releaseLogin.await(10, TimeUnit.SECONDS));
            } else if (password.equals(newPassword))
                resetHasUserLock.countDown();
            return call.callRealMethod();
        }).when(encoder).matches(any(CharSequence.class), eq(stored));
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Cookie> login = executor.submit(() -> cookie(email));
            assertTrue(loginChecking.await(10, TimeUnit.SECONDS));
            Future<Integer> reset = executor.submit(() -> mvc
                    .perform(post("/api/v1/auth/reset-password").header("Origin", "http://localhost:5173")
                            .contentType("application/json")
                            .content(json.writeValueAsString(Map.of("token", raw, "password", newPassword))))
                    .andReturn().getResponse().getStatus());
            try {
                assertFalse(resetHasUserLock.await(400, TimeUnit.MILLISECONDS),
                        "Password mutation must wait until credential-check/session creation releases the user row");
            } finally {
                releaseLogin.countDown();
            }
            Cookie obsolete = login.get(20, TimeUnit.SECONDS);
            assertEquals(200, reset.get(20, TimeUnit.SECONDS));
            mvc.perform(get("/api/v1/me").cookie(obsolete)).andExpect(status().isUnauthorized());
        } finally {
            releaseLogin.countDown();
        }
    }

    @Test
    void PoolSizedParallelLoginsDoNotWaitForNestedAuthenticationEventConnections() throws Exception {
        Set<String> hashes = new HashSet<>();
        List<String> accounts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String id = user("MEMBER");
            accounts.add(email(id));
            hashes.add(db.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, id));
        }
        CountDownLatch readers = new CountDownLatch(10), release = new CountDownLatch(1);
        doAnswer(call -> {
            if (hashes.contains(call.getArgument(1).toString())) {
                readers.countDown();
                assertTrue(release.await(15, TimeUnit.SECONDS));
            }
            return call.callRealMethod();
        }).when(encoder).matches(eq(PASSWORD), anyString());
        try (ExecutorService executor = Executors.newFixedThreadPool(10)) {
            List<Future<Integer>> responses = new ArrayList<>();
            for (int i = 0; i < accounts.size(); i++) {
                String account = accounts.get(i), ip = "198.51.100." + (i + 1);
                responses.add(executor.submit(() -> mvc.perform(post("/api/v1/auth/login").with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                }).header("Origin", "http://localhost:5173").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", account, "password", PASSWORD)))).andReturn()
                        .getResponse().getStatus()));
            }
            try {
                assertTrue(readers.await(15, TimeUnit.SECONDS),
                        "All pool-sized credential transactions must reach the test barrier");
            } finally {
                release.countDown();
            }
            for (Future<Integer> response : responses)
                assertEquals(200, response.get(25, TimeUnit.SECONDS));
            for (String account : accounts)
                assertEquals(1,
                        db.queryForObject(
                                "SELECT COUNT(*) FROM authentication_attempts WHERE email_hash=? AND outcome='SUCCESS'",
                                Integer.class, Tokens.hash(account)));
        } finally {
            release.countDown();
        }
    }

    @Test
    void queuedMutationRechecksRemovedCustomGrantAndCurrentBaseRole() throws Exception {
        String roleId = UUID.randomUUID().toString();
        db.update("INSERT INTO custom_roles(id,tenant_id,name,base_role,permissions) VALUES(?,?,?,'HR',?)", roleId,
                tenant, "Temporary HR", "[\"POST /api/v1/org/records/{kind}\"]");
        db.update("UPDATE memberships SET role='HR',custom_role_id=? WHERE tenant_id=? AND user_id=?", roleId, tenant,
                member);
        Cookie staff = cookie(email(member));
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            CountDownLatch started = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Future<Integer>> pending = new java.util.concurrent.atomic.AtomicReference<>();
            new TransactionTemplate(manager).execute(state -> {
                db.queryForObject("SELECT id FROM tenants WHERE id=? FOR UPDATE", String.class, tenant);
                pending.set(executor.submit(() -> {
                    started.countDown();
                    return mvc
                            .perform(post("/api/v1/org/records/departments").cookie(staff)
                                    .header("Origin", "http://localhost:5173").contentType("application/json")
                                    .content("{\"name\":\"Revoked grant department\"}"))
                            .andReturn().getResponse().getStatus();
                }));
                awaitMutationWait(started, pending.get());
                db.update("UPDATE custom_roles SET permissions='[]',version=version+1 WHERE tenant_id=? AND id=?",
                        tenant, roleId);
                return null;
            });
            assertEquals(403, pending.get().get(15, TimeUnit.SECONDS));
            assertEquals(0,
                    db.queryForObject("SELECT COUNT(*) FROM business_records WHERE tenant_id=? AND kind='departments'",
                            Integer.class, tenant));
            db.update("UPDATE custom_roles SET permissions=? WHERE tenant_id=? AND id=?",
                    "[\"POST /api/v1/org/records/{kind}\"]", tenant, roleId);
            CountDownLatch nextStarted = new CountDownLatch(1);
            new TransactionTemplate(manager).execute(state -> {
                db.queryForObject("SELECT id FROM tenants WHERE id=? FOR UPDATE", String.class, tenant);
                pending.set(executor.submit(() -> {
                    nextStarted.countDown();
                    return mvc
                            .perform(post("/api/v1/org/records/departments").cookie(staff)
                                    .header("Origin", "http://localhost:5173").contentType("application/json")
                                    .content("{\"name\":\"Demoted role department\"}"))
                            .andReturn().getResponse().getStatus();
                }));
                awaitMutationWait(nextStarted, pending.get());
                db.update("UPDATE memberships SET role='MEMBER',custom_role_id=NULL WHERE tenant_id=? AND user_id=?",
                        tenant, member);
                return null;
            });
            assertEquals(403, pending.get().get(15, TimeUnit.SECONDS));
            assertEquals(0,
                    db.queryForObject("SELECT COUNT(*) FROM business_records WHERE tenant_id=? AND kind='departments'",
                            Integer.class, tenant));
        }
    }

    private void awaitMutationWait(CountDownLatch started, Future<Integer> pending) {
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < until) {
                assertFalse(pending.isDone(), "Mutation cannot complete while administrator holds its tenant row");
                try {
                    Integer waits = db.queryForObject(
                            "SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='tenants' AND l.LOCK_DATA LIKE ?",
                            Integer.class, "%" + tenant + "%");
                    if (waits != null && waits > 0)
                        return;
                } catch (org.springframework.dao.DataAccessException restrictedTestUser) {
                    Thread.sleep(400);
                    assertFalse(pending.isDone());
                    return;
                }
                Thread.sleep(20);
            }
            fail("Mutation did not reach the controlled tenant-lock wait");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
