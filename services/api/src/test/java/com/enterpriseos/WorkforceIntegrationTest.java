package com.enterpriseos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.condition.EnabledIf;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"app.seed-demo=false"})
@EnabledIf("com.enterpriseos.TestDatabase#available")
class WorkforceIntegrationTest {
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry r) {
        TestDatabase.configure(r);
    }
    @Autowired OperationsController ops;
    @Autowired JdbcTemplate db;
    Principal admin,member,reviewer,foreignAdmin;
    String tenant;
    private String uuid(){return UUID.randomUUID().toString();}
    @BeforeEach void seed() {
        tenant=uuid();String foreign=uuid();
        for(String t:List.of(tenant,foreign))db.update("INSERT INTO tenants(id,name,slug,status,timezone,created_at) VALUES(?,?,?,'ACTIVE','UTC',CURRENT_TIMESTAMP(6))",t,"Test",t);
        admin=user(tenant,"ADMIN");member=user(tenant,"MEMBER");reviewer=user(tenant,"HR");foreignAdmin=user(foreign,"ADMIN");
    }
    private Principal user(String t,String role) {
        String user=uuid();db.update("INSERT INTO users(id,name,email,password_hash,status,created_at) VALUES(?,?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6))",user,role,user+"@example.test","unused");
        db.update("INSERT INTO memberships(id,tenant_id,user_id,role,status) VALUES(?,?,?,?,'ACTIVE')",uuid(),t,user,role);
        return new Principal(user,t,role,user+"@example.test",role);
    }
    @Test void employeeCodesAreGeneratedAndLifecycleDetailsAreValidated(){
        var first=ops.create(admin,"employees",Map.of("name","First employee","education",List.of(Map.of("institution","College","qualification","Degree","year",2020))));
        var second=ops.create(admin,"employees",Map.of("name","Second employee"));assertEquals("EMP-000001",first.get("employeeCode"));assertEquals("EMP-000002",second.get("employeeCode"));
        assertEquals(400,assertThrows(ApiException.class,()->ops.create(admin,"employees",Map.of("name","Invalid","joinDate","2026-11-11","probationEndDate","2026-01-01"))).status());
        assertEquals(400,assertThrows(ApiException.class,()->ops.create(admin,"employees",Map.of("name","Invalid","onboardingChecklist",List.of(Map.of("title","Verify","status","invented"))))).status());
    }
    @Test void timesheetSubmissionRequiresIndependentReviewAndLocksApprovedHours() {
        var project=ops.create(admin,"projects",Map.of("name","Timesheet project","memberIds",List.of(member.userId())));
        var task=ops.create(admin,"tasks",Map.of("name","Work","projectId",project.get("id"),"assigneeId",member.userId()));
        var entry=ops.create(member,"timeEntries",Map.of("taskId",task.get("id"),"date","2026-11-11","hours",4,"reviewStatus","APPROVED"));
        String id=entry.get("id").toString();assertEquals("DRAFT",entry.get("reviewStatus"));
        var pending=ops.submitTime(member,id);assertEquals("PENDING",pending.get("reviewStatus"));
        assertEquals(409,assertThrows(ApiException.class,()->ops.update(member,"timeEntries",id,Map.of("version",pending.get("version"),"hours",8))).status());
        assertEquals(403,assertThrows(ApiException.class,()->ops.reviewTime(member,id,Map.of("decision","APPROVED","reason","Own"))).status());
        var approved=ops.reviewTime(admin,id,Map.of("decision","APPROVED","reason","Verified deliverable"));assertEquals("APPROVED",approved.get("reviewStatus"));
        assertEquals(409,assertThrows(ApiException.class,()->ops.reviewTime(admin,id,Map.of("decision","APPROVED","reason","Duplicate"))).status());
        assertEquals(3,ops.history(member,"timeEntries",id).size());
    }
    @Test void historicEmployeeSnapshotsDoNotLeakPrivateFields() {
        var employee=ops.create(admin,"employees",Map.of("name","Private person","employeeCode","H1","salary",60000,"customMedicalField","Sensitive"));
        String id=employee.get("id").toString();ops.update(admin,"employees",id,Map.of("version",1,"salary",70000));
        for(var history:ops.history(member,"employees",id)){assertFalse(((Map<?,?>)history.get("after")).containsKey("salary"));assertFalse(((Map<?,?>)history.get("after")).containsKey("customMedicalField"));}
        assertEquals(404,assertThrows(ApiException.class,()->ops.history(foreignAdmin,"employees",id)).status());
    }
    @Test void cannotReferenceAnotherTenantsDepartment() {
        Map<String,Object> department=ops.create(foreignAdmin,"departments",Map.of("name","Private"));
        ApiException e=assertThrows(ApiException.class,()->ops.create(admin,"employees",Map.of("name","Employee","employeeCode","E1","departmentId",department.get("id"))));
        assertEquals(404,e.status());assertTrue(ops.list(admin,"employees").isEmpty());
    }
    @Test void confidentialAndUnknownEmployeeFieldsAreDeniedByDefault() {
        ops.create(admin,"employees",Map.of("name","Employee","employeeCode","E1","salary",50000,"emergencyContact","Secret","customMedicalField","Private"));
        Map<String,Object> directory=ops.list(member,"employees").getFirst();
        assertFalse(directory.containsKey("salary"));assertFalse(directory.containsKey("emergencyContact"));assertFalse(directory.containsKey("customMedicalField"));
        assertTrue(ops.list(admin,"employees").getFirst().containsKey("salary"));
    }
    @Test void staleTaskUpdatesAndDependencyCyclesAreRejected() {
        Map<String,Object> project=ops.create(admin,"projects",Map.of("name","Delivery","memberIds",List.of(member.userId())));
        Map<String,Object> a=ops.create(admin,"tasks",Map.of("name","A","projectId",project.get("id"),"assigneeId",member.userId()));
        Map<String,Object> b=ops.create(admin,"tasks",Map.of("name","B","projectId",project.get("id"),"dependencyIds",List.of(a.get("id"))));
        assertEquals(409,assertThrows(ApiException.class,()->ops.update(admin,"tasks",a.get("id").toString(),Map.of("version",1,"dependencyIds",List.of(b.get("id"))))).status());
        ops.update(admin,"tasks",a.get("id").toString(),Map.of("version",1,"status","IN_PROGRESS"));
        assertEquals(409,assertThrows(ApiException.class,()->ops.update(admin,"tasks",a.get("id").toString(),Map.of("version",1,"status","DONE"))).status());
    }
    @Test void attendanceCannotBeDoubleOpenedOrSelfApproved() {
        Map<String,Object> attendance=ops.checkIn(admin);
        assertEquals(409,assertThrows(ApiException.class,()->ops.checkIn(admin)).status());
        Map<String,Object> correction=ops.requestCorrection(admin,Map.of("attendanceId",attendance.get("id"),"reason","Wrong time","inAt",attendance.get("inAt")));
        assertEquals(403,assertThrows(ApiException.class,()->ops.correctionDecision(admin,correction.get("id").toString(),Map.of("decision","APPROVED"))).status());
        ops.correctionDecision(reviewer,correction.get("id").toString(),Map.of("decision","APPROVED"));
        Integer events=db.queryForObject("SELECT COUNT(*) FROM attendance_events WHERE tenant_id=? AND attendance_id=?",Integer.class,tenant,attendance.get("id"));
        assertEquals(2,events);
    }
    @Test void concurrentApprovalPostsOnceAndCancellationReversesOnce() throws Exception {
        ops.create(admin,"leavePolicies",Map.of("name","Annual","type","Annual","annualDays",1,"workingDays",List.of(1,2,3,4,5)));
        Map<String,Object> leave=ops.requestLeave(member,Map.of("type","Annual","startDate","2027-01-04","endDate","2027-01-04","reason","Personal"));
        String leaveId=leave.get("id").toString();
        try(ExecutorService executor=Executors.newFixedThreadPool(2)) {
            CountDownLatch start=new CountDownLatch(1);
            Callable<Integer> decision=()->{start.await();try{ops.leaveDecision(reviewer,leaveId,Map.of("decision","APPROVED"));return 200;}catch(ApiException e){return e.status();}};
            Future<Integer> first=executor.submit(decision),second=executor.submit(decision);start.countDown();
            List<Integer> codes=new ArrayList<>(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS)));Collections.sort(codes);assertEquals(List.of(200,409),codes);
        }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM leave_ledger WHERE tenant_id=? AND request_id=?",Integer.class,tenant,leaveId));
        ops.cancelLeave(member,leaveId);
        assertEquals(409,assertThrows(ApiException.class,()->ops.cancelLeave(member,leaveId)).status());
        assertEquals(0,db.queryForObject("SELECT SUM(delta) FROM leave_ledger WHERE tenant_id=? AND request_id=?",java.math.BigDecimal.class,tenant,leaveId).signum());
    }
    @Test void archivedProjectAndInactiveAssigneeCannotReceiveChanges() {
        Map<String,Object> project=ops.create(admin,"projects",Map.of("name","Delivery","memberIds",List.of(member.userId())));
        db.update("UPDATE memberships SET status='INACTIVE' WHERE tenant_id=? AND user_id=?",tenant,member.userId());
        assertEquals(400,assertThrows(ApiException.class,()->ops.create(admin,"tasks",Map.of("name","Task","projectId",project.get("id"),"assigneeId",member.userId()))).status());
        ops.update(admin,"projects",project.get("id").toString(),Map.of("version",1,"status","ARCHIVED","memberIds",List.of()));
        assertEquals(409,assertThrows(ApiException.class,()->ops.create(admin,"tasks",Map.of("name","Task","projectId",project.get("id")))).status());
    }
    @Test void twoStageApprovalDoesNotDeductUntilHrFinalization() {
        Principal manager=user(tenant,"MANAGER");
        Map<String,Object> managerEmployee=ops.create(admin,"employees",Map.of("name","Manager","employeeCode","M1","userId",manager.userId()));
        ops.create(admin,"employees",Map.of("name","Member","employeeCode","E1","userId",member.userId(),"managerId",managerEmployee.get("id")));
        ops.create(admin,"leavePolicies",Map.of("name","Annual","type","Annual","annualDays",10,"approvalLevels",2));
        Map<String,Object> leave=ops.requestLeave(member,Map.of("type","Annual","startDate","2027-01-04","endDate","2027-01-04","reason","Personal"));String leaveId=leave.get("id").toString();
        assertEquals(403,assertThrows(ApiException.class,()->ops.leaveDecision(reviewer,leaveId,Map.of("decision","APPROVED"))).status());
        Map<String,Object> first=ops.leaveDecision(manager,leaveId,Map.of("decision","APPROVED"));assertEquals("PENDING",first.get("status"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM leave_ledger WHERE tenant_id=?",Integer.class,tenant));
        assertEquals("APPROVED",ops.leaveDecision(reviewer,leaveId,Map.of("decision","APPROVED")).get("status"));assertEquals(2,ops.leaveHistory(member,leaveId).size());
    }
    @Test void monthlyAccrualAndCarryForwardAreComputedFromPolicy() {
        ops.create(admin,"leavePolicies",Map.of("name","Annual","type","Annual","annualDays",12,"accrualMode","MONTHLY","carryForwardDays",3));
        Map<String,Object> balance=ops.balances(member).getFirst();
        assertEquals(java.time.LocalDate.now().getMonthValue()+3,((java.math.BigDecimal)balance.get("allowance")).intValueExact());
    }
    @Test void manualAdjustmentAppearsInPrivateBalanceHistory() {
        ops.create(admin,"leavePolicies",Map.of("name","Annual","type","Annual","annualDays",12));
        Map<String,Object> adjustment=ops.adjustLeave(reviewer,Map.of("userId",member.userId(),"type","Annual","year",java.time.LocalDate.now().getYear(),"days",2,"reason","Opening balance correction"));
        Map<String,Object> history=ops.leaveLedger(member).stream().filter(row->adjustment.get("id").equals(row.get("id"))).findFirst().orElseThrow();
        assertEquals("MANUAL_ADJUSTMENT",history.get("source"));assertNull(history.get("requestId"));assertEquals(2,((java.math.BigDecimal)history.get("delta")).intValueExact());
        assertTrue(ops.leaveLedger(foreignAdmin).isEmpty());
        assertEquals(14,((java.math.BigDecimal)ops.balances(member).getFirst().get("remaining")).intValueExact());
    }
}
