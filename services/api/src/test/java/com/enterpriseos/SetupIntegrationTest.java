package com.enterpriseos;

import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.setup-enabled=true"}) @AutoConfigureMockMvc
@EnabledIf("externalAvailable")
class SetupIntegrationTest {
    static boolean externalAvailable(){return !System.getenv().getOrDefault("TEST_DB_URL","").isBlank();}
    @DynamicPropertySource static void database(DynamicPropertyRegistry r)throws Exception {
        String url=System.getenv("TEST_DB_URL"),user=System.getenv().getOrDefault("TEST_DB_USERNAME","root"),password=System.getenv().getOrDefault("TEST_DB_PASSWORD","");
        String schema="eos_setup_test_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()){statement.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");}
        int query=url.indexOf('?'),slash=url.lastIndexOf('/',query<0?url.length():query);String isolated=url.substring(0,slash+1)+schema+(query<0?"":url.substring(query));
        r.add("spring.datasource.url",()->isolated);r.add("spring.datasource.username",()->user);r.add("spring.datasource.password",()->password);
    }
    @Autowired MockMvc mvc;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;
    @Test void simultaneousFirstRunCanCreateOnlyOneRealCompanyWithoutSamples()throws Exception {
        mvc.perform(get("/api/v1/setup")).andExpect(status().isOk()).andExpect(jsonPath("$.required").value(true));
        String body=json.writeValueAsString(Map.of("companyName","Setup verification","ownerName","Owner","ownerEmail","owner@example.test","ownerPassword","PrivateOwnerPass!123","platformEmail","platform@example.test","platformPassword","PrivatePlatformPass!123","timezone","Asia/Kolkata"));
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        Callable<Integer> create=()->{gate.await();return mvc.perform(post("/api/v1/setup").header("Origin","http://localhost:5173").contentType("application/json").content(body)).andReturn().getResponse().getStatus();};
        var first=pool.submit(create);var second=pool.submit(create);gate.countDown();var statuses=java.util.List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS));pool.shutdown();
        assertTrue(statuses.contains(200));assertTrue(statuses.contains(409));assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM users",Integer.class));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM tenants",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM business_records",Integer.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM business_entities",Integer.class));
        mvc.perform(get("/api/v1/setup")).andExpect(status().isOk()).andExpect(jsonPath("$.required").value(false));
        mvc.perform(post("/api/v1/auth/login").header("Origin","http://localhost:5173").contentType("application/json").content("{\"email\":\"owner@example.test\",\"password\":\"PrivateOwnerPass!123\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
    }
}
