package com.enterpriseos;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Phase 2 application service. Tenant locks serialize state transitions across all API instances. */
@RestController
@RequestMapping("/api/v1/org")
public class OperationsController {
    private static final Set<String> KINDS = Set.of("departments","branches","teams","designations","employees","shifts","shiftAssignments","leavePolicies","projects","sprints","milestones","tasks","timeEntries");
    private static final Set<String> HR_KINDS = Set.of("departments","branches","teams","designations","employees","shifts","shiftAssignments","leavePolicies");
    private static final Set<String> PRIVATE_FIELDS = Set.of("salary","bankAccount","personalEmail","emergencyContact","nationalId","taxId","dateOfBirth");
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AuditService audit;
    private final EntitlementService entitlements;
    private final MentionService mentions;
    public OperationsController(JdbcTemplate db, ObjectMapper json, AuditService audit,EntitlementService entitlements,MentionService mentions) { this.db=db;this.json=json;this.audit=audit;this.entitlements=entitlements;this.mentions=mentions; }

    private ApiException error(int status,String message) { return new ApiException(status,message); }
    private String id() { return UUID.randomUUID().toString(); }
    private String str(Map<String,Object> m,String key) { return Objects.toString(m.get(key),"").trim(); }
    private String required(Map<String,Object> m,String key) { String s=str(m,key);if(s.isEmpty()||s.length()>1000)throw error(400,key+" is required and must be at most 1000 characters");return s; }
    private boolean role(Principal p,String... roles) { return Arrays.asList(roles).contains(p.role()); }
    private void org(Principal p) { if(p.tenantId()==null||p.tenantId().isBlank()||role(p,"PLATFORM_ADMIN"))throw error(403,"An active organization membership is required"); }
    private void allow(Principal p,String... roles) { org(p);if(!role(p,roles))throw error(403,"Your role cannot perform this action"); }
    private void lock(Principal p) {
        org(p);String status=db.queryForObject("SELECT status FROM tenants WHERE id=? FOR UPDATE",String.class,p.tenantId());
        if(!"ACTIVE".equals(status))throw error(403,"This organization is suspended");
        Integer active=db.queryForObject("SELECT COUNT(*) FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.tenant_id=? AND m.user_id=? AND m.status='ACTIVE' AND u.status='ACTIVE' AND m.role=?",Integer.class,p.tenantId(),p.userId(),p.role());
        if(active==null||active!=1)throw error(403,"Membership or permissions changed. Sign in again");
        Map<String,Object> subscription=entitlements.subscription(p.tenantId());if(!subscription.isEmpty())entitlements.writable(subscription);
    }
    private void kind(String k) { if(!KINDS.contains(k))throw error(404,"Unknown record collection"); }
    private String encode(Object o) { try{return json.writeValueAsString(o);}catch(Exception e){throw error(400,"Invalid record data");} }
    private Map<String,Object> decode(String s) { try{return json.readValue(s,new TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception e){throw new IllegalStateException("Invalid stored record",e);} }
    private LocalDate date(String s,String field) { try{return LocalDate.parse(s);}catch(Exception e){throw error(400,field+" must be an ISO date");} }
    private Instant instant(String s,String field) { try{return Instant.parse(s);}catch(Exception e){throw error(400,field+" must be an ISO UTC timestamp");} }
    private List<String> ids(Map<String,Object> m,String key) { Object value=m.get(key);if(value==null)return List.of();if(!(value instanceof List<?> list))throw error(400,key+" must be an array");return list.stream().map(Objects::toString).distinct().toList(); }
    private BigDecimal number(Map<String,Object> m,String key,BigDecimal fallback) { try{return m.get(key)==null?fallback:new BigDecimal(m.get(key).toString());}catch(Exception e){throw error(400,key+" must be a number");} }
    private Map<String,Object> record(Principal p,String k,String recordId) {
        List<Map<String,Object>> rows=db.query("SELECT id,payload,version FROM business_records WHERE tenant_id=? AND kind=? AND id=?",(rs,n)->{Map<String,Object> m=decode(rs.getString("payload"));m.put("id",rs.getString("id"));m.put("version",rs.getLong("version"));return m;},p.tenantId(),k,recordId);
        if(rows.isEmpty())throw error(404,"Record was not found in your organization");return rows.getFirst();
    }
    private List<Map<String,Object>> all(Principal p,String k) {
        return db.query("SELECT id,payload,version FROM business_records WHERE tenant_id=? AND kind=? ORDER BY created_at,id",(rs,n)->{Map<String,Object> m=decode(rs.getString("payload"));m.put("id",rs.getString("id"));m.put("version",rs.getLong("version"));return m;},p.tenantId(),k);
    }
    private void activeUser(Principal p,String userId) {
        Integer count=db.queryForObject("SELECT COUNT(*) FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.tenant_id=? AND m.user_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'",Integer.class,p.tenantId(),userId);
        if(count==null||count!=1)throw error(400,"User reference must be an active member of this organization");
        boolean offboarded=all(p,"employees").stream().anyMatch(e->userId.equals(str(e,"userId"))&&"OFFBOARDED".equals(str(e,"status")));
        if(offboarded)throw error(400,"An offboarded employee cannot receive assignments");
    }
    private void ref(Principal p,Map<String,Object> m,String field,String k) { if(!str(m,field).isEmpty())record(p,k,str(m,field)); }
    private boolean projectVisible(Principal p,Map<String,Object> project) { return role(p,"ADMIN","MANAGER","AUDITOR")||p.userId().equals(str(project,"ownerId"))||ids(project,"memberIds").contains(p.userId()); }
    private Map<String,Object> project(Principal p,String projectId,boolean write) {
        Map<String,Object> project=record(p,"projects",projectId);
        if(!projectVisible(p,project))throw error(403,"You do not have access to this project");
        if(write&&"ARCHIVED".equals(str(project,"status")))throw error(409,"Archived projects are read-only");return project;
    }
    private boolean visible(Principal p,String k,Map<String,Object> m) {
        if(k.equals("projects"))return projectVisible(p,m);
        if(Set.of("tasks","sprints","milestones").contains(k))return projectVisible(p,record(p,"projects",str(m,"projectId")));
        if(k.equals("timeEntries")){if(role(p,"ADMIN","MANAGER","AUDITOR"))return true;return p.userId().equals(str(m,"userId"));}
        if(k.equals("shiftAssignments")&&!role(p,"ADMIN","HR","MANAGER","AUDITOR"))return p.userId().equals(str(record(p,"employees",str(m,"employeeId")),"userId"));
        return true;
    }
    private Map<String,Object> publicRecord(Principal p,String k,Map<String,Object> source) {
        Map<String,Object> m=new LinkedHashMap<>(source);
        if(k.equals("employees")&&!role(p,"ADMIN","HR")&&!p.userId().equals(str(m,"userId"))) {
            m.keySet().retainAll(Set.of("id","version","name","employeeCode","status","departmentId","managerId","designationId","branchId","teamId","employmentType","joinDate"));
        }
        return m;
    }
    @GetMapping("/records/{kind}")
    public List<Map<String,Object>> list(@RequestAttribute Principal principal,@PathVariable String kind) { org(principal);kind(kind);return all(principal,kind).stream().filter(m->visible(principal,kind,m)).map(m->publicRecord(principal,kind,m)).toList(); }

    @PostMapping("/records/{kind}") @Transactional
    public Map<String,Object> create(@RequestAttribute Principal principal,@PathVariable String kind,@RequestBody Map<String,Object> body) { return save(principal,kind,null,body); }
    @PutMapping("/records/{kind}/{id}") @Transactional
    public Map<String,Object> update(@RequestAttribute Principal principal,@PathVariable String kind,@PathVariable String id,@RequestBody Map<String,Object> body) { return save(principal,kind,id,body); }

    private Map<String,Object> save(Principal p,String k,String recordId,Map<String,Object> body) {
        kind(k);lock(p);
        entitlements.require(p,HR_KINDS.contains(k)?"HR":"PROJECTS",recordId==null);
        boolean selfProfile=k.equals("employees")&&recordId!=null&&!role(p,"ADMIN","HR");
        if(selfProfile) {
            allow(p,"MANAGER","MEMBER","SALES","PROCUREMENT","FINANCE","PAYROLL");Map<String,Object> employee=record(p,k,recordId);
            if(!p.userId().equals(str(employee,"userId")))throw error(403,"You can edit only your own profile");
            if(!Set.of("version","name","phone","personalEmail","emergencyContact").containsAll(body.keySet()))throw error(403,"Employment and sensitive HR fields can only be changed by HR");
        } else if(HR_KINDS.contains(k))allow(p,"ADMIN","HR");else if(k.equals("tasks")||k.equals("timeEntries"))allow(p,"ADMIN","MANAGER","MEMBER","HR","SALES","PROCUREMENT","FINANCE","PAYROLL");else allow(p,"ADMIN","MANAGER");
        Map<String,Object> old=recordId==null?null:record(p,k,recordId);
        if(k.equals("timeEntries")&&old!=null&&Set.of("PENDING","APPROVED").contains(str(old,"reviewStatus")))throw error(409,"Submitted or approved time entries cannot be edited. A reviewer must reject the entry before changes.");
        if(old!=null&&number(body,"version",BigDecimal.valueOf(-1)).longValue()!=((Number)old.get("version")).longValue())throw error(409,"Record changed. Reload before saving");
        Map<String,Object> m=new LinkedHashMap<>();if(old!=null)m.putAll(old);m.putAll(body);m.remove("id");m.remove("version");m.remove("tenantId");m.remove("tenant_id");
        if(k.equals("employees"))alias(m,"employeeCode","code");if(k.equals("tasks"))alias(m,"name","title");
        if(k.equals("shiftAssignments")){alias(m,"effectiveFrom","startDate");alias(m,"effectiveTo","endDate");}
        if(k.equals("leavePolicies"))alias(m,"annualDays","annualAllowance");
        validate(p,k,recordId,m,old);
        if(k.equals("timeEntries")){m.put("reviewStatus","DRAFT");m.remove("reviewerId");m.remove("reviewReason");m.remove("reviewedAt");}
        String newId=recordId==null?id():recordId;long version=old==null?1:((Number)old.get("version")).longValue()+1;
        if(encode(m).length()>200000)throw error(400,"Record is too large");
        if(old==null)db.update("INSERT INTO business_records(id,tenant_id,kind,payload,version) VALUES(?,?,?,?,1)",newId,p.tenantId(),k,encode(m));
        else db.update("UPDATE business_records SET payload=?,version=?,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND kind=? AND id=?",encode(m),version,p.tenantId(),k,newId);
        change(p,k,newId,version,old==null?"CREATE":"UPDATE",old==null?Map.of():old,m);
        audit.log(p,old==null?"CREATE":"UPDATE",k,newId);m.put("id",newId);m.put("version",version);return publicRecord(p,k,m);
    }
    private void alias(Map<String,Object> m,String target,String source) { if(!m.containsKey(target)&&m.containsKey(source))m.put(target,m.get(source)); }
    private void change(Principal p,String kind,String recordId,long version,String action,Map<String,Object> before,Map<String,Object> after){db.update("INSERT INTO workforce_changes(id,tenant_id,record_id,kind,record_version,actor_id,action,before_value,after_value) VALUES (?,?,?,?,?,?,?,?,?)",id(),p.tenantId(),recordId,kind,version,p.userId(),action,encode(before),encode(after));}
    @GetMapping("/records/{kind}/{id}/history")
    public List<Map<String,Object>> history(@RequestAttribute Principal principal,@PathVariable String kind,@PathVariable String id){org(principal);kind(kind);Map<String,Object> current=record(principal,kind,id);if(!visible(principal,kind,current))throw error(403,"This record is not visible to your account");return db.query("SELECT * FROM workforce_changes WHERE tenant_id=? AND kind=? AND record_id=? ORDER BY record_version DESC LIMIT 200",(rs,n)->{Map<String,Object> view=new LinkedHashMap<>();view.put("id",rs.getString("id"));view.put("version",rs.getLong("record_version"));view.put("actorId",rs.getString("actor_id"));view.put("action",rs.getString("action"));view.put("changedAt",rs.getTimestamp("created_at").toInstant());view.put("before",publicRecord(principal,kind,decode(rs.getString("before_value"))));view.put("after",publicRecord(principal,kind,decode(rs.getString("after_value"))));return view;},principal.tenantId(),kind,id);}
    @PostMapping("/time-entries/{id}/submit") @Transactional
    public Map<String,Object> submitTime(@RequestAttribute Principal principal,@PathVariable String id){
        allow(principal,"ADMIN","HR","MANAGER","MEMBER","SALES","PROCUREMENT","FINANCE","PAYROLL");lock(principal);entitlements.require(principal,"PROJECTS",false);Map<String,Object> entry=record(principal,"timeEntries",id);
        if(!principal.userId().equals(str(entry,"userId")))throw error(403,"Submit only your own time entries");
        if(Set.of("PENDING","APPROVED").contains(str(entry,"reviewStatus")))throw error(409,"This time entry has already been submitted");
        Map<String,Object> task=record(principal,"tasks",str(entry,"taskId"));project(principal,str(task,"projectId"),true);
        return timeTransition(principal,entry,"PENDING","");
    }
    @PostMapping("/time-entries/{id}/decision") @Transactional
    public Map<String,Object> reviewTime(@RequestAttribute Principal principal,@PathVariable String id,@RequestBody Map<String,Object> body){
        allow(principal,"ADMIN","MANAGER");lock(principal);entitlements.require(principal,"PROJECTS",false);Map<String,Object> entry=record(principal,"timeEntries",id);
        if(principal.userId().equals(str(entry,"userId")))throw error(403,"Another authorized reviewer must decide your time entry");
        if(!"PENDING".equals(str(entry,"reviewStatus")))throw error(409,"Only pending time entries can be reviewed");
        String decision=required(body,"decision"),reason=required(body,"reason");if(!Set.of("APPROVED","REJECTED").contains(decision))throw error(400,"Choose APPROVED or REJECTED");
        Map<String,Object> task=record(principal,"tasks",str(entry,"taskId"));project(principal,str(task,"projectId"),true);
        return timeTransition(principal,entry,decision,reason);
    }
    private Map<String,Object> timeTransition(Principal p,Map<String,Object> entry,String status,String reason){Map<String,Object> before=new LinkedHashMap<>(entry);long version=((Number)entry.get("version")).longValue()+1;entry.put("reviewStatus",status);if(!status.equals("PENDING")){entry.put("reviewerId",p.userId());entry.put("reviewReason",reason);entry.put("reviewedAt",Instant.now().toString());}db.update("UPDATE business_records SET payload=?,version=?,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND kind='timeEntries' AND id=?",encode(entry),version,p.tenantId(),entry.get("id"));change(p,"timeEntries",str(entry,"id"),version,"TIME_"+status,before,entry);entry.put("version",version);audit.log(p,"TIME_"+status,"timeEntries",str(entry,"id"));return entry;}
    private void enumValue(Map<String,Object> m,String field,String defaultValue,String... values) { if(str(m,field).isEmpty())m.put(field,defaultValue);if(!Arrays.asList(values).contains(str(m,field)))throw error(400,"Invalid "+field); }
    private void dates(Map<String,Object> m,String from,String to) { if(!str(m,from).isEmpty()){LocalDate start=date(str(m,from),from);if(!str(m,to).isEmpty()&&date(str(m,to),to).isBefore(start))throw error(400,to+" must not be before "+from);} }
    private void validate(Principal p,String k,String recordId,Map<String,Object> m,Map<String,Object> old) {
        if(Set.of("projects","tasks").contains(k)){if(!str(m,"startDate").isBlank())date(str(m,"startDate"),"startDate");if(!str(m,"dueDate").isBlank())date(str(m,"dueDate"),"dueDate");dates(m,"startDate","dueDate");}
        if(!Set.of("shiftAssignments","timeEntries").contains(k))required(m,"name");
        if(Set.of("departments","branches","teams","designations").contains(k)){ref(p,m,"parentId",k);if(recordId!=null&&!str(m,"parentId").isEmpty())cycle(p,k,recordId,str(m,"parentId"),"parentId");}
        if(k.equals("employees")) {
            if(str(m,"employeeCode").isEmpty()&&old==null){long next=all(p,k).stream().map(e->str(e,"employeeCode")).filter(code->code.matches("EMP-[0-9]{1,12}")).mapToLong(code->Long.parseLong(code.substring(4))).max().orElse(0)+1;m.put("employeeCode",String.format(java.util.Locale.ROOT,"EMP-%06d",next));}
            required(m,"employeeCode");ref(p,m,"departmentId","departments");ref(p,m,"branchId","branches");ref(p,m,"teamId","teams");ref(p,m,"designationId","designations");ref(p,m,"managerId","employees");
            dates(m,"joinDate","contractEndDate");dates(m,"joinDate","probationEndDate");
            for(String collection:List.of("education","workHistory","onboardingChecklist","offboardingChecklist")){
                if(m.get(collection)==null)continue;if(!(m.get(collection) instanceof List<?> entries)||entries.size()>50)throw error(400,collection+" must contain at most 50 entries");
                for(Object item:entries){if(!(item instanceof Map<?,?> raw))throw error(400,collection+" entries must be objects");Map<String,Object> entry=new LinkedHashMap<>();raw.forEach((key,value)->entry.put(String.valueOf(key),value));
                    if(collection.equals("education")){required(entry,"institution");required(entry,"qualification");if(!str(entry,"year").isBlank()){int year=number(entry,"year",BigDecimal.ZERO).intValueExact();if(year<1900||year>2200)throw error(400,"Education year must be 1900 to 2200");}}
                    else if(collection.equals("workHistory")){required(entry,"employer");required(entry,"title");dates(entry,"fromDate","toDate");}
                    else{required(entry,"title");if(!Set.of("PENDING","DONE").contains(str(entry,"status")))throw error(400,"Checklist status must be PENDING or DONE");}
                }
            }
            if(recordId!=null&&!str(m,"managerId").isEmpty())cycle(p,k,recordId,str(m,"managerId"),"managerId");
            if(!str(m,"userId").isEmpty()) {
                Integer linked=db.queryForObject("SELECT COUNT(*) FROM memberships WHERE tenant_id=? AND user_id=?",Integer.class,p.tenantId(),str(m,"userId"));
                if(linked==null||linked!=1)throw error(400,"Employee account must belong to this organization");
            }
            for(Map<String,Object> e:all(p,k))if(!str(e,"id").equals(recordId)&&(str(e,"employeeCode").equalsIgnoreCase(str(m,"employeeCode"))||(!str(m,"userId").isEmpty()&&str(e,"userId").equals(str(m,"userId")))))throw error(409,"Employee code and linked user must be unique in the organization");
            enumValue(m,"status","ACTIVE","ONBOARDING","ACTIVE","OFFBOARDED");if(!str(m,"joinDate").isEmpty())date(str(m,"joinDate"),"joinDate");
            if(!str(m,"effectiveDate").isEmpty())date(str(m,"effectiveDate"),"effectiveDate");
            List<Object> history=new ArrayList<>();if(old!=null&&old.get("history") instanceof List<?> list)history.addAll(list);
            if(old==null||!str(old,"status").equals(str(m,"status"))||!str(old,"departmentId").equals(str(m,"departmentId"))||!str(old,"managerId").equals(str(m,"managerId")))history.add(Map.of("status",str(m,"status"),"departmentId",str(m,"departmentId"),"managerId",str(m,"managerId"),"effectiveDate",str(m,"effectiveDate").isEmpty()?LocalDate.now().toString():str(m,"effectiveDate"),"actorId",p.userId(),"recordedAt",Instant.now().toString()));
            m.put("history",history);
            if("OFFBOARDED".equals(str(m,"status"))&&!str(m,"userId").isEmpty()) {
                if(p.userId().equals(str(m,"userId")))throw error(409,"Ask another administrator to offboard your own employment record");
                String linkedUser=str(m,"userId");
                Integer admins=db.queryForObject("SELECT COUNT(*) FROM memberships WHERE tenant_id=? AND role='ADMIN' AND status='ACTIVE'",Integer.class,p.tenantId());
                Integer targetAdmin=db.queryForObject("SELECT COUNT(*) FROM memberships WHERE tenant_id=? AND user_id=? AND role='ADMIN' AND status='ACTIVE'",Integer.class,p.tenantId(),linkedUser);
                if(targetAdmin!=null&&targetAdmin>0&&admins!=null&&admins<=1)throw error(409,"The last active administrator cannot be offboarded");
                db.update("UPDATE memberships SET status='INACTIVE' WHERE tenant_id=? AND user_id=?",p.tenantId(),linkedUser);
                db.update("UPDATE sessions SET revoked_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND user_id=? AND revoked_at IS NULL",p.tenantId(),linkedUser);
            }
        }
        if(k.equals("shifts")) { try{LocalTime.parse(required(m,"startTime"));LocalTime.parse(required(m,"endTime"));}catch(DateTimeException e){throw error(400,"Shift times must use HH:mm");}if(number(m,"graceMinutes",BigDecimal.ZERO).signum()<0)throw error(400,"Grace minutes cannot be negative"); }
        if(k.equals("shiftAssignments")) {
            record(p,"employees",required(m,"employeeId"));record(p,"shifts",required(m,"shiftId"));LocalDate from=date(required(m,"effectiveFrom"),"effectiveFrom");LocalDate to=str(m,"effectiveTo").isEmpty()?LocalDate.MAX:date(str(m,"effectiveTo"),"effectiveTo");if(to.isBefore(from))throw error(400,"Assignment end must follow start");
            for(Map<String,Object> e:all(p,k))if(!str(e,"id").equals(recordId)&&str(e,"employeeId").equals(str(m,"employeeId"))){LocalDate a=date(str(e,"effectiveFrom"),"effectiveFrom");LocalDate b=str(e,"effectiveTo").isEmpty()?LocalDate.MAX:date(str(e,"effectiveTo"),"effectiveTo");if(!from.isAfter(b)&&!to.isBefore(a))throw error(409,"Shift assignment overlaps an existing assignment");}
        }
        if(k.equals("leavePolicies")) { if(str(m,"type").isEmpty())m.put("type",str(m,"name"));BigDecimal allowance=number(m,"annualDays",BigDecimal.valueOf(-1));if(allowance.signum()<0||allowance.compareTo(BigDecimal.valueOf(366))>0)throw error(400,"annualDays must be between 0 and 366");for(Map<String,Object> e:all(p,k))if(!str(e,"id").equals(recordId)&&str(e,"type").equalsIgnoreCase(str(m,"type"))&&str(e,"year").equals(str(m,"year")))throw error(409,"Leave policy type and year already exist");if(m.get("workingDays") instanceof List<?> list)for(Object day:list){int n;try{n=Integer.parseInt(day.toString());}catch(Exception e){throw error(400,"workingDays must contain weekday numbers 1 through 7");}if(n<1||n>7)throw error(400,"workingDays must contain weekday numbers 1 through 7");} }
        if(k.equals("leavePolicies")) {
            enumValue(m,"accrualMode","ANNUAL","ANNUAL","MONTHLY");if(!str(m,"year").isEmpty()){int policyYear=number(m,"year",BigDecimal.ZERO).intValue();if(policyYear<2000||policyYear>2200)throw error(400,"Policy year must be between 2000 and 2200");}
            for(String field:List.of("carryForwardDays","escalationDays")){BigDecimal value=number(m,field,BigDecimal.ZERO);if(value.signum()<0||value.compareTo(BigDecimal.valueOf(366))>0)throw error(400,field+" must be between 0 and 366");m.put(field,value);}
            BigDecimal levels=number(m,"approvalLevels",BigDecimal.ONE);if(levels.compareTo(BigDecimal.ONE)!=0&&levels.compareTo(BigDecimal.valueOf(2))!=0)throw error(400,"approvalLevels must be 1 or 2");m.put("approvalLevels",levels.intValue());
            for(String holiday:ids(m,"holidays"))date(holiday,"holiday");
            if(m.get("delegations") instanceof List<?> delegations)for(Object item:delegations){if(!(item instanceof Map<?,?> raw))throw error(400,"Delegations must be objects");Map<String,Object> delegate=new LinkedHashMap<>();raw.forEach((key,value)->delegate.put(key.toString(),value));activeUser(p,required(delegate,"fromUserId"));activeUser(p,required(delegate,"toUserId"));dates(delegate,"startDate","endDate");date(required(delegate,"startDate"),"startDate");date(required(delegate,"endDate"),"endDate");}
        }
        if(k.equals("projects")) { enumValue(m,"status","ACTIVE","ACTIVE","ARCHIVED");if(old!=null&&"ARCHIVED".equals(str(old,"status"))&&!"ACTIVE".equals(str(m,"status")))throw error(409,"Restore the archived project before editing");if(str(m,"ownerId").isEmpty())m.put("ownerId",p.userId());activeUser(p,str(m,"ownerId"));for(String user:ids(m,"memberIds"))activeUser(p,user); }
        if(Set.of("tasks","sprints","milestones").contains(k)) {
            Map<String,Object> proj=project(p,required(m,"projectId"),true);if(old!=null&&!str(old,"projectId").equals(str(m,"projectId")))throw error(400,"Moving records between projects is not supported");
            if(k.equals("tasks")) {
                if(old!=null&&!role(p,"ADMIN","MANAGER")&&!p.userId().equals(str(old,"assigneeId")))throw error(403,"Only the assignee or a project manager can edit this task");
                if(!str(m,"assigneeId").isEmpty()){activeUser(p,str(m,"assigneeId"));if(!ids(proj,"memberIds").contains(str(m,"assigneeId"))&&!str(proj,"ownerId").equals(str(m,"assigneeId")))throw error(400,"Task assignee must belong to the project");}
                enumValue(m,"status","TODO","TODO","IN_PROGRESS","REVIEW","DONE");enumValue(m,"priority","MEDIUM","LOW","MEDIUM","HIGH","CRITICAL");
                for(String field:List.of("sprintId","parentId"))if(!str(m,field).isEmpty()){Map<String,Object> ref=record(p,field.equals("sprintId")?"sprints":"tasks",str(m,field));if(!str(ref,"projectId").equals(str(m,"projectId")))throw error(400,field+" must belong to the same project");}
                if(recordId!=null&&!str(m,"parentId").isEmpty())cycle(p,k,recordId,str(m,"parentId"),"parentId");
                for(String dependency:ids(m,"dependencyIds")){Map<String,Object> task=record(p,"tasks",dependency);if(!str(task,"projectId").equals(str(m,"projectId")))throw error(400,"Dependencies must belong to the same project");if(recordId!=null)dependencyCycle(p,recordId,dependency,new HashSet<>());}
                m.put("comments",old==null?List.of():old.getOrDefault("comments",List.of()));
                List<Object> history=new ArrayList<>();if(old!=null&&old.get("history") instanceof List<?> list)history.addAll(list);history.add(Map.of("status",str(m,"status"),"assigneeId",str(m,"assigneeId"),"actorId",p.userId(),"at",Instant.now().toString()));m.put("history",history);
            } else dates(m,"startDate","endDate");
        }
        if(k.equals("timeEntries")) { Map<String,Object> task=record(p,"tasks",required(m,"taskId"));project(p,str(task,"projectId"),true);if(old!=null&&!p.userId().equals(str(old,"userId"))&&!role(p,"ADMIN","MANAGER"))throw error(403,"You can only edit your own time entries");m.put("userId",old==null?p.userId():old.get("userId"));date(required(m,"date"),"date");BigDecimal hours=number(m,"hours",BigDecimal.ZERO);if(hours.signum()<=0||hours.compareTo(BigDecimal.valueOf(24))>0)throw error(400,"Hours must be greater than zero and at most 24"); }
        if(!str(m,"dueDate").isEmpty())date(str(m,"dueDate"),"dueDate");
    }
    private void cycle(Principal p,String k,String target,String next,String field) { Set<String> seen=new HashSet<>();while(!next.isEmpty()){if(next.equals(target)||!seen.add(next))throw error(409,"Hierarchy cannot contain a cycle");next=str(record(p,k,next),field);} }
    private void dependencyCycle(Principal p,String target,String next,Set<String> seen) { if(next.equals(target))throw error(409,"Task dependencies cannot contain a cycle");if(seen.add(next))for(String n:ids(record(p,"tasks",next),"dependencyIds"))dependencyCycle(p,target,n,seen); }

    @GetMapping("/attendance")
    public List<Map<String,Object>> attendance(@RequestAttribute Principal principal) { org(principal);return attendanceRows(principal).stream().filter(m->role(principal,"ADMIN","HR","MANAGER","AUDITOR")||principal.userId().equals(str(m,"userId"))).map(m->attendanceSummary(principal,m)).toList(); }
    private ZoneId tenantZone(Principal p) { String timezone=db.queryForObject("SELECT timezone FROM tenants WHERE id=?",String.class,p.tenantId());try{return ZoneId.of(timezone==null?"UTC":timezone);}catch(DateTimeException e){return ZoneOffset.UTC;} }
    private Map<String,Object> attendanceSummary(Principal p,Map<String,Object> row) {
        row.put("source","MANUAL");Instant start=instant(str(row,"inAt"),"inAt"),end=row.get("outAt")==null?Instant.now():instant(str(row,"outAt"),"outAt");long worked=Math.max(0,Duration.between(start,end).toMinutes());row.put("workedMinutes",worked);row.put("lateMinutes",0L);row.put("overtimeMinutes",0L);
        Optional<Map<String,Object>> employee=all(p,"employees").stream().filter(e->str(row,"userId").equals(str(e,"userId"))).findFirst();
        if(employee.isEmpty())return row;ZoneId zone=tenantZone(p);LocalDate day=date(str(row,"workDate"),"workDate");
        Optional<Map<String,Object>> assignment=all(p,"shiftAssignments").stream().filter(a->str(employee.get(),"id").equals(str(a,"employeeId"))&&!day.isBefore(date(str(a,"effectiveFrom"),"effectiveFrom"))&&(str(a,"effectiveTo").isEmpty()||!day.isAfter(date(str(a,"effectiveTo"),"effectiveTo")))).findFirst();
        if(assignment.isPresent()) {Map<String,Object> shift=record(p,"shifts",str(assignment.get(),"shiftId"));LocalTime from=LocalTime.parse(str(shift,"startTime")),to=LocalTime.parse(str(shift,"endTime"));ZonedDateTime scheduledStart=day.atTime(from).atZone(zone),scheduledEnd=(to.isAfter(from)?day:day.plusDays(1)).atTime(to).atZone(zone);long grace=number(shift,"graceMinutes",BigDecimal.ZERO).longValue();row.put("lateMinutes",Math.max(0,Duration.between(scheduledStart.toInstant(),start).toMinutes()-grace));row.put("overtimeMinutes",Math.max(0,worked-Duration.between(scheduledStart,scheduledEnd).toMinutes()));row.put("shiftId",shift.get("id"));}
        return row;
    }
    @GetMapping("/attendance/{id}/events")
    public List<Map<String,Object>> attendanceEvents(@RequestAttribute Principal principal,@PathVariable String id) { org(principal);Map<String,Object> row=attendanceRows(principal).stream().filter(m->id.equals(m.get("id"))).findFirst().orElseThrow(()->error(404,"Attendance was not found"));if(!role(principal,"ADMIN","HR","MANAGER","AUDITOR")&&!principal.userId().equals(str(row,"userId")))throw error(403,"You cannot read this attendance history");return db.queryForList("SELECT actor_id AS actorId,event_type AS eventType,event_value AS eventValue,created_at AS createdAt FROM attendance_events WHERE tenant_id=? AND attendance_id=? ORDER BY created_at,id",principal.tenantId(),id); }
    private List<Map<String,Object>> attendanceRows(Principal p) { return db.query("SELECT a.*,u.name FROM attendance a JOIN users u ON u.id=a.user_id WHERE a.tenant_id=? ORDER BY a.work_date DESC,a.in_at DESC",(rs,n)->{Map<String,Object> m=new LinkedHashMap<>();m.put("id",rs.getString("id"));m.put("userId",rs.getString("user_id"));m.put("name",rs.getString("name"));m.put("inAt",rs.getString("in_at"));m.put("outAt",rs.getString("out_at"));m.put("workDate",rs.getString("work_date"));m.put("status",rs.getString("out_at")==null?"CHECKED_IN":"CHECKED_OUT");m.put("version",rs.getLong("version"));return m;},p.tenantId()); }
    private void attendanceEvent(Principal p,String attendanceId,String type,Object value) { db.update("INSERT INTO attendance_events(id,tenant_id,attendance_id,actor_id,event_type,event_value) VALUES(?,?,?,?,?,?)",id(),p.tenantId(),attendanceId,p.userId(),type,encode(Map.of("source","MANUAL","values",value))); }
    @PostMapping("/attendance/check-in") @Transactional
    public Map<String,Object> checkIn(@RequestAttribute Principal principal) {
        allow(principal,"ADMIN","HR","MANAGER","MEMBER","SALES","PROCUREMENT","FINANCE","PAYROLL");lock(principal);activeUser(principal,principal.userId());
        if(attendanceRows(principal).stream().anyMatch(m->principal.userId().equals(str(m,"userId"))&&m.get("outAt")==null))throw error(409,"You already have an open attendance session");
        String recordId=id();String now=Instant.now().toString();String timezone=db.queryForObject("SELECT timezone FROM tenants WHERE id=?",String.class,principal.tenantId());ZoneId zone;try{zone=ZoneId.of(timezone==null?"UTC":timezone);}catch(DateTimeException e){zone=ZoneOffset.UTC;}
        db.update("INSERT INTO attendance(id,tenant_id,user_id,in_at,work_date) VALUES(?,?,?,?,?)",recordId,principal.tenantId(),principal.userId(),now,LocalDate.now(zone));attendanceEvent(principal,recordId,"CHECK_IN",Map.of("inAt",now));audit.log(principal,"CHECK_IN","attendance",recordId);return attendanceRows(principal).stream().filter(m->recordId.equals(m.get("id"))).findFirst().orElseThrow();
    }
    @PostMapping("/attendance/check-out") @Transactional
    public Map<String,Object> checkOut(@RequestAttribute Principal principal) {
        allow(principal,"ADMIN","HR","MANAGER","MEMBER","SALES","PROCUREMENT","FINANCE","PAYROLL");lock(principal);Map<String,Object> row=attendanceRows(principal).stream().filter(m->principal.userId().equals(str(m,"userId"))&&m.get("outAt")==null).findFirst().orElseThrow(()->error(409,"There is no open attendance session"));String now=Instant.now().toString();db.update("UPDATE attendance SET out_at=?,version=version+1 WHERE tenant_id=? AND id=?",now,principal.tenantId(),row.get("id"));attendanceEvent(principal,str(row,"id"),"CHECK_OUT",Map.of("outAt",now));audit.log(principal,"CHECK_OUT","attendance",str(row,"id"));row.put("outAt",now);row.put("status","CHECKED_OUT");return row;
    }
    @GetMapping("/attendance/corrections")
    public List<Map<String,Object>> corrections(@RequestAttribute Principal principal) { org(principal);return correctionRows(principal).stream().filter(m->role(principal,"ADMIN","HR","MANAGER","AUDITOR")||principal.userId().equals(str(m,"userId"))).toList(); }
    private List<Map<String,Object>> correctionRows(Principal p) { return db.query("SELECT c.*,u.name FROM attendance_corrections c JOIN users u ON u.id=c.user_id WHERE c.tenant_id=? ORDER BY c.created_at DESC",(rs,n)->{Map<String,Object> m=new LinkedHashMap<>();m.put("id",rs.getString("id"));m.put("attendanceId",rs.getString("attendance_id"));m.put("userId",rs.getString("user_id"));m.put("name",rs.getString("name"));m.put("reason",rs.getString("reason"));m.put("inAt",rs.getString("in_at"));m.put("outAt",rs.getString("out_at"));m.put("status",rs.getString("status"));m.put("baseVersion",rs.getLong("base_version"));return m;},p.tenantId()); }
    @PostMapping("/attendance/corrections") @Transactional
    public Map<String,Object> requestCorrection(@RequestAttribute Principal principal,@RequestBody Map<String,Object> body) {
        allow(principal,"ADMIN","HR","MANAGER","MEMBER","SALES","PROCUREMENT","FINANCE","PAYROLL");lock(principal);String attendanceId=required(body,"attendanceId");Map<String,Object> row=attendanceRows(principal).stream().filter(m->attendanceId.equals(m.get("id"))).findFirst().orElseThrow(()->error(404,"Attendance was not found"));if(!principal.userId().equals(str(row,"userId")))throw error(403,"Submit corrections only for your own attendance");
        String inAt=required(body,"inAt"),outAt=str(body,"outAt");Instant start=instant(inAt,"inAt");if(start.isAfter(Instant.now()))throw error(400,"Attendance cannot be in the future");if(!outAt.isEmpty()){Instant end=instant(outAt,"outAt");if(end.isBefore(start)||end.isAfter(Instant.now()))throw error(400,"Check-out must follow check-in and cannot be in the future");}
        Instant effectiveEnd=outAt.isEmpty()?Instant.now():instant(outAt,"outAt");
        if(Duration.between(start,effectiveEnd).compareTo(Duration.ofHours(48))>0)throw error(400,"Attendance sessions cannot exceed 48 hours; split the correction by work session");
        for(Map<String,Object> other:attendanceRows(principal))if(principal.userId().equals(str(other,"userId"))&&!attendanceId.equals(str(other,"id"))){Instant otherStart=instant(str(other,"inAt"),"inAt"),otherEnd=other.get("outAt")==null?Instant.now():instant(str(other,"outAt"),"outAt");if(start.isBefore(otherEnd)&&effectiveEnd.isAfter(otherStart))throw error(409,"Corrected attendance overlaps another session");}
        String recordId=id();db.update("INSERT INTO attendance_corrections(id,tenant_id,attendance_id,user_id,reason,in_at,out_at,base_version) VALUES(?,?,?,?,?,?,?,?)",recordId,principal.tenantId(),attendanceId,principal.userId(),required(body,"reason"),inAt,outAt.isEmpty()?null:outAt,row.get("version"));audit.log(principal,"REQUEST_CORRECTION","attendance",attendanceId);return correctionRows(principal).stream().filter(m->recordId.equals(m.get("id"))).findFirst().orElseThrow();
    }
    @PostMapping("/attendance/corrections/{id}/decision") @Transactional
    public Map<String,Object> correctionDecision(@RequestAttribute Principal principal,@PathVariable String id,@RequestBody Map<String,Object> body) {
        allow(principal,"ADMIN","HR","MANAGER");lock(principal);Map<String,Object> row=correctionRows(principal).stream().filter(m->id.equals(m.get("id"))).findFirst().orElseThrow(()->error(404,"Correction was not found"));if(principal.userId().equals(str(row,"userId")))throw error(403,"You cannot review your own attendance correction");requireSupervisor(principal,str(row,"userId"));String decision=decision(body);if(!"PENDING".equals(str(row,"status")))throw error(409,"Correction has already been decided");
        if(decision.equals("APPROVED")){Map<String,Object> attendance=attendanceRows(principal).stream().filter(m->str(row,"attendanceId").equals(m.get("id"))).findFirst().orElseThrow();if(!attendance.get("version").equals(row.get("baseVersion")))throw error(409,"Attendance changed after this correction was requested");if(row.get("outAt")==null&&attendanceRows(principal).stream().anyMatch(m->!m.get("id").equals(attendance.get("id"))&&str(m,"userId").equals(str(row,"userId"))&&m.get("outAt")==null))throw error(409,"This correction would create overlapping open sessions");attendanceEvent(principal,str(row,"attendanceId"),"CORRECTION",Map.of("before",attendance,"after",row));db.update("UPDATE attendance SET in_at=?,out_at=?,work_date=?,version=version+1 WHERE tenant_id=? AND id=?",row.get("inAt"),row.get("outAt"),instant(str(row,"inAt"),"inAt").atZone(tenantZone(principal)).toLocalDate(),principal.tenantId(),row.get("attendanceId"));}
        db.update("UPDATE attendance_corrections SET status=?,reviewer_id=? WHERE tenant_id=? AND id=?",decision,principal.userId(),principal.tenantId(),id);audit.log(principal,"CORRECTION_"+decision,"attendance",str(row,"attendanceId"));row.put("status",decision);return row;
    }
    private String decision(Map<String,Object> body) { String d=required(body,"decision");if(!Set.of("APPROVED","REJECTED").contains(d))throw error(400,"Decision must be APPROVED or REJECTED");return d; }

    private List<Map<String,Object>> leaveRows(Principal p) { return db.query("SELECT l.*,u.name FROM leave_requests l JOIN users u ON u.id=l.user_id WHERE l.tenant_id=? ORDER BY l.created_at DESC",(rs,n)->{Map<String,Object> m=new LinkedHashMap<>();m.put("id",rs.getString("id"));m.put("userId",rs.getString("user_id"));m.put("name",rs.getString("name"));m.put("type",rs.getString("leave_type"));m.put("startDate",rs.getString("start_date"));m.put("endDate",rs.getString("end_date"));m.put("days",rs.getBigDecimal("days"));m.put("reason",rs.getString("reason"));m.put("status",rs.getString("status"));m.put("approvedLevels",rs.getInt("approved_levels"));m.put("requiredLevels",rs.getInt("required_levels"));return m;},p.tenantId()); }
    @GetMapping("/leave/requests")
    public List<Map<String,Object>> leaves(@RequestAttribute Principal principal) { org(principal);return leaveRows(principal).stream().filter(m->role(principal,"ADMIN","HR","MANAGER","AUDITOR")||principal.userId().equals(str(m,"userId"))||delegatedRequest(principal,m)).toList(); }
    private boolean delegatedRequest(Principal actor,Map<String,Object> request) { if(!"PENDING".equals(str(request,"status")))return false;String snapshot=db.queryForObject("SELECT policy_snapshot FROM leave_requests WHERE tenant_id=? AND id=?",String.class,actor.tenantId(),request.get("id"));return isDelegate(actor,managerUser(actor,str(request,"userId")),decode(snapshot)); }
    private Map<String,Object> policy(Principal p,String type) { return policy(p,type,LocalDate.now().getYear()); }
    private Map<String,Object> policy(Principal p,String type,int year) { List<Map<String,Object>> matching=all(p,"leavePolicies").stream().filter(m->type.equalsIgnoreCase(str(m,"type"))||type.equalsIgnoreCase(str(m,"name"))).toList();return matching.stream().filter(m->str(m,"year").equals(Integer.toString(year))).findFirst().or(()->matching.stream().filter(m->str(m,"year").isEmpty()).findFirst()).orElseThrow(()->error(400,"An administrator must configure this leave policy for the requested year first")); }
    private BigDecimal allowance(Principal p,String user,String type,int year) { return entitlement(p,user,type,year,policy(p,type,year)); }
    private BigDecimal entitlement(Principal p,String user,String type,int year,Map<String,Object> rule) {
        BigDecimal annual=number(rule,"annualDays",BigDecimal.ZERO),base=annual;
        if("MONTHLY".equals(str(rule,"accrualMode"))){LocalDate today=LocalDate.now();int months=year<today.getYear()?12:year>today.getYear()?0:today.getMonthValue();base=annual.multiply(BigDecimal.valueOf(months)).divide(BigDecimal.valueOf(12),2,java.math.RoundingMode.DOWN);}
        BigDecimal cap=number(rule,"carryForwardDays",BigDecimal.ZERO),previousAnnual;
        try{previousAnnual=number(policy(p,type,year-1),"annualDays",BigDecimal.ZERO);}catch(ApiException ignored){previousAnnual=BigDecimal.ZERO;}
        BigDecimal carry=previousAnnual.subtract(used(p,user,type,year-1)).max(BigDecimal.ZERO).min(cap);
        return base.add(carry);
    }
    private BigDecimal used(Principal p,String user,String type,int year) { BigDecimal delta=db.queryForObject("SELECT COALESCE(SUM(ll.delta),0) FROM leave_ledger ll JOIN leave_requests lr ON lr.id=ll.request_id WHERE ll.tenant_id=? AND ll.user_id=? AND ll.leave_type=? AND YEAR(lr.start_date)=?",BigDecimal.class,p.tenantId(),user,type,year);BigDecimal adjustment=db.queryForObject("SELECT COALESCE(SUM(delta),0) FROM leave_adjustments WHERE tenant_id=? AND user_id=? AND leave_type=? AND entitlement_year=?",BigDecimal.class,p.tenantId(),user,type,year);return delta.negate().subtract(adjustment); }
    @GetMapping("/leave/balances")
    public List<Map<String,Object>> balances(@RequestAttribute Principal principal) { org(principal);return all(principal,"leavePolicies").stream().filter(m->str(m,"year").isEmpty()||str(m,"year").equals(Integer.toString(LocalDate.now().getYear()))).filter(m->str(policy(principal,str(m,"type")),"id").equals(str(m,"id"))).map(m->{String type=str(m,"type");BigDecimal a=entitlement(principal,principal.userId(),type,LocalDate.now().getYear(),m),u=used(principal,principal.userId(),type,LocalDate.now().getYear());Map<String,Object> result=new LinkedHashMap<>();result.put("type",type);result.put("allowance",a);result.put("used",u);result.put("remaining",a.subtract(u));return result;}).toList(); }
    @PostMapping("/leave/requests") @Transactional
    public Map<String,Object> requestLeave(@RequestAttribute Principal principal,@RequestBody Map<String,Object> body) {
        allow(principal,"ADMIN","HR","MANAGER","MEMBER","SALES","PROCUREMENT","FINANCE","PAYROLL");lock(principal);activeUser(principal,principal.userId());String type=required(body,"type");LocalDate from=date(required(body,"startDate"),"startDate"),to=date(required(body,"endDate"),"endDate");Map<String,Object> policy=policy(principal,type,from.getYear());type=str(policy,"type");if(to.isBefore(from)||from.getYear()!=to.getYear())throw error(400,"Leave dates must be ordered within one calendar year");if(from.until(to).getYears()>1||from.plusDays(366).isBefore(to))throw error(400,"Leave request is too long");
        for(Map<String,Object> row:leaveRows(principal))if(principal.userId().equals(str(row,"userId"))&&Set.of("PENDING","APPROVED").contains(str(row,"status"))&&!from.isAfter(date(str(row,"endDate"),"endDate"))&&!to.isBefore(date(str(row,"startDate"),"startDate")))throw error(409,"Leave overlaps an existing pending or approved request");
        Set<Integer> working=new HashSet<>(Set.of(1,2,3,4,5));if(policy.get("workingDays") instanceof List<?> list){working.clear();for(Object day:list)working.add(Integer.parseInt(day.toString()));}List<String> holidays=ids(policy,"holidays");long days=from.datesUntil(to.plusDays(1)).filter(d->working.contains(d.getDayOfWeek().getValue())&&!holidays.contains(d.toString())).count();if(days==0)throw error(400,"Leave contains no working days");if(allowance(principal,principal.userId(),type,from.getYear()).subtract(used(principal,principal.userId(),type,from.getYear())).compareTo(BigDecimal.valueOf(days))<0)throw error(409,"Insufficient leave entitlement");
        int levels=number(policy,"approvalLevels",BigDecimal.ONE).intValue();if(levels<1||levels>2)throw error(400,"Configured approvals support one manager stage and optional HR finalization");
        String requestId=id();db.update("INSERT INTO leave_requests(id,tenant_id,user_id,leave_type,start_date,end_date,days,reason,required_levels,policy_snapshot) VALUES(?,?,?,?,?,?,?,?,?,?)",requestId,principal.tenantId(),principal.userId(),type,from,to,days,required(body,"reason"),levels,encode(policy));audit.log(principal,"REQUEST_LEAVE","leave",requestId);return leaveRows(principal).stream().filter(m->requestId.equals(m.get("id"))).findFirst().orElseThrow();
    }
    private Map<String,Object> leave(Principal p,String id) { return leaveRows(p).stream().filter(m->id.equals(m.get("id"))).findFirst().orElseThrow(()->error(404,"Leave request was not found")); }
    private void ledger(Principal p,Map<String,Object> row,BigDecimal delta,String reason) { db.update("INSERT INTO leave_ledger(id,tenant_id,user_id,leave_type,request_id,delta,reason,actor_id) VALUES(?,?,?,?,?,?,?,?)",id(),p.tenantId(),row.get("userId"),row.get("type"),row.get("id"),delta,reason,p.userId()); }
    @PostMapping("/leave/requests/{id}/decision") @Transactional
    public Map<String,Object> leaveDecision(@RequestAttribute Principal principal,@PathVariable String id,@RequestBody Map<String,Object> body) {
        allow(principal,"ADMIN","HR","MANAGER","MEMBER","SALES","PROCUREMENT","FINANCE","PAYROLL");lock(principal);Map<String,Object> row=leave(principal,id);
        if(principal.userId().equals(str(row,"userId")))throw error(403,"You cannot approve your own leave");
        if(!"PENDING".equals(str(row,"status")))throw error(409,"Leave has already been decided");
        Map<String,Object> state=db.queryForMap("SELECT required_levels,approved_levels,reviewer_id,policy_snapshot,created_at FROM leave_requests WHERE tenant_id=? AND id=?",principal.tenantId(),id);
        int approved=((Number)state.get("approved_levels")).intValue(),required=((Number)state.get("required_levels")).intValue();
        if(principal.userId().equals(Objects.toString(state.get("reviewer_id"),"")))throw error(409,"Each approval stage requires a different reviewer");
        if(approved>0&&!role(principal,"ADMIN","HR"))throw error(403,"Final approval requires HR or organization admin");
        Map<String,Object> snapshot=decode(Objects.toString(state.get("policy_snapshot")));
        String managerUser=managerUser(principal,str(row,"userId"));
        boolean delegated=isDelegate(principal,managerUser,snapshot);
        int escalation=number(snapshot,"escalationDays",BigDecimal.ZERO).intValue();
        java.sql.Timestamp submitted=db.queryForObject("SELECT created_at FROM leave_requests WHERE tenant_id=? AND id=?",java.sql.Timestamp.class,principal.tenantId(),id);
        boolean escalated=escalation>0&&submitted!=null&&!submitted.toInstant().plus(Duration.ofDays(escalation)).isAfter(Instant.now());
        if(approved==0) {
            boolean assigned=principal.userId().equals(managerUser)||delegated;
            if(!assigned&&!(role(principal,"ADMIN","HR")&&(required==1||managerUser.isEmpty()||escalated)))throw error(403,"The assigned manager or active delegate must review this stage before escalation");
        }
        String decision=decision(body),status=decision;
        if("APPROVED".equals(decision)){
            approved++;
            if(approved<required)status="PENDING";
            else {BigDecimal days=(BigDecimal)row.get("days");
                BigDecimal entitlement=entitlement(principal,str(row,"userId"),str(row,"type"),date(str(row,"startDate"),"startDate").getYear(),snapshot);
                if(entitlement.subtract(used(principal,str(row,"userId"),str(row,"type"),date(str(row,"startDate"),"startDate").getYear())).compareTo(days)<0)throw error(409,"Insufficient leave entitlement");
                ledger(principal,row,days.negate(),"APPROVED");
            }
        }
        int step=((Number)state.get("approved_levels")).intValue()+1;
        db.update("INSERT INTO leave_decisions(id,tenant_id,request_id,actor_id,decision,step_number) VALUES(?,?,?,?,?,?)",id(),principal.tenantId(),id,principal.userId(),decision,step);
        db.update("UPDATE leave_requests SET status=?,reviewer_id=?,approved_levels=? WHERE tenant_id=? AND id=?",status,principal.userId(),approved,principal.tenantId(),id);
        audit.log(principal,"LEAVE_"+decision,"leave",id);row.put("status",status);row.put("approvedLevels",approved);row.put("requiredLevels",required);return row;
    }
    private void requireSupervisor(Principal actor,String subjectUserId) {
        if(role(actor,"ADMIN","HR"))return;
        Map<String,Object> employee=all(actor,"employees").stream().filter(m->subjectUserId.equals(str(m,"userId"))).findFirst().orElseThrow(()->error(403,"A reporting manager must be configured before manager approval"));
        String managerId=str(employee,"managerId");if(managerId.isEmpty()||!actor.userId().equals(str(record(actor,"employees",managerId),"userId")))throw error(403,"Only the assigned reporting manager may review this request");
    }
    private String managerUser(Principal actor,String subjectUserId) {
        Optional<Map<String,Object>> employee=all(actor,"employees").stream().filter(m->subjectUserId.equals(str(m,"userId"))).findFirst();
        if(employee.isEmpty()||str(employee.get(),"managerId").isEmpty())return "";
        return str(record(actor,"employees",str(employee.get(),"managerId")),"userId");
    }
    private boolean isDelegate(Principal actor,String managerUser,Map<String,Object> snapshot) {
        if(snapshot.get("delegations") instanceof List<?> list)for(Object item:list)if(item instanceof Map<?,?> delegate) {
            String from=Objects.toString(delegate.get("fromUserId"),""),to=Objects.toString(delegate.get("toUserId"),"");
            if(from.equals(managerUser)&&to.equals(actor.userId())) { LocalDate start=date(Objects.toString(delegate.get("startDate"),""),"startDate"),end=date(Objects.toString(delegate.get("endDate"),""),"endDate");LocalDate today=LocalDate.now();if(!today.isBefore(start)&&!today.isAfter(end))return true; }
        }
        return false;
    }
    @GetMapping("/leave/requests/{id}/history")
    public List<Map<String,Object>> leaveHistory(@RequestAttribute Principal principal,@PathVariable String id) {
        org(principal);Map<String,Object> request=leave(principal,id);if(!role(principal,"ADMIN","HR","MANAGER","AUDITOR")&&!principal.userId().equals(str(request,"userId"))&&!delegatedRequest(principal,request))throw error(403,"You cannot read this request's decisions");
        return db.queryForList("SELECT actor_id AS actorId,decision,step_number AS step,created_at AS createdAt FROM leave_decisions WHERE tenant_id=? AND request_id=? ORDER BY step_number",principal.tenantId(),id);
    }
    @PostMapping("/leave/adjustments") @Transactional
    public Map<String,Object> adjustLeave(@RequestAttribute Principal principal,@RequestBody Map<String,Object> body) {
        allow(principal,"ADMIN","HR");lock(principal);String user=required(body,"userId"),type=required(body,"type");activeUser(principal,user);type=str(policy(principal,type),"type");
        int year=number(body,"year",BigDecimal.valueOf(LocalDate.now().getYear())).intValue();if(year<2000||year>2200)throw error(400,"Invalid entitlement year");BigDecimal delta=number(body,"days",BigDecimal.ZERO);if(delta.signum()==0||delta.abs().compareTo(BigDecimal.valueOf(366))>0)throw error(400,"Adjustment must be nonzero and at most 366 days");
        if(allowance(principal,user,type,year).subtract(used(principal,user,type,year)).add(delta).signum()<0)throw error(409,"Adjustment would create a negative leave balance");String adjustmentId=id();
        db.update("INSERT INTO leave_adjustments(id,tenant_id,user_id,leave_type,entitlement_year,delta,reason,actor_id) VALUES(?,?,?,?,?,?,?,?)",adjustmentId,principal.tenantId(),user,type,year,delta,required(body,"reason"),principal.userId());audit.log(principal,"ADJUST_LEAVE","leave_adjustments",adjustmentId);return Map.of("id",adjustmentId,"userId",user,"type",type,"year",year,"delta",delta);
    }
    @PostMapping("/leave/requests/{id}/cancel") @Transactional
    public Map<String,Object> cancelLeave(@RequestAttribute Principal principal,@PathVariable String id) {
        allow(principal,"ADMIN","HR","MANAGER","MEMBER","SALES","PROCUREMENT","FINANCE","PAYROLL");lock(principal);Map<String,Object> row=leave(principal,id);if(!principal.userId().equals(str(row,"userId"))&&!role(principal,"ADMIN","HR"))throw error(403,"You can only cancel your own leave");if(!Set.of("PENDING","APPROVED").contains(str(row,"status")))throw error(409,"This request cannot be cancelled");if("APPROVED".equals(str(row,"status")))ledger(principal,row,(BigDecimal)row.get("days"),"CANCELLED");db.update("UPDATE leave_requests SET status='CANCELLED' WHERE tenant_id=? AND id=?",principal.tenantId(),id);audit.log(principal,"CANCEL_LEAVE","leave",id);row.put("status","CANCELLED");return row;
    }
    @GetMapping("/leave/ledger")
    public List<Map<String,Object>> leaveLedger(@RequestAttribute Principal principal) { org(principal);return db.query("SELECT id,user_id,leave_type,request_id,delta,reason,created_at FROM (SELECT id,user_id,leave_type,request_id,delta,reason,created_at FROM leave_ledger WHERE tenant_id=? UNION ALL SELECT id,user_id,leave_type,NULL AS request_id,delta,reason,created_at FROM leave_adjustments WHERE tenant_id=?) history ORDER BY created_at DESC,id",(rs,n)->{Map<String,Object> m=new LinkedHashMap<>();m.put("id",rs.getString("id"));m.put("userId",rs.getString("user_id"));m.put("type",rs.getString("leave_type"));m.put("requestId",rs.getString("request_id"));m.put("delta",rs.getBigDecimal("delta"));m.put("reason",rs.getString("reason"));m.put("createdAt",rs.getString("created_at"));m.put("source",rs.getString("request_id")==null?"MANUAL_ADJUSTMENT":"LEAVE_REQUEST");return m;},principal.tenantId(),principal.tenantId()).stream().filter(m->role(principal,"ADMIN","HR","MANAGER","AUDITOR")||principal.userId().equals(str(m,"userId"))).toList(); }
    @GetMapping("/tasks/{id}/comments")
    public List<?> comments(@RequestAttribute Principal principal,@PathVariable String id) { org(principal);Map<String,Object> task=record(principal,"tasks",id);project(principal,str(task,"projectId"),false);return task.get("comments") instanceof List<?> list?list:List.of(); }
    @PostMapping("/tasks/{id}/comments") @Transactional
    public Map<String,Object> comment(@RequestAttribute Principal principal,@PathVariable String id,@RequestBody Map<String,Object> body) { allow(principal,"ADMIN","MANAGER","HR","MEMBER","SALES","PROCUREMENT","FINANCE","PAYROLL");lock(principal);Map<String,Object> task=record(principal,"tasks",id);project(principal,str(task,"projectId"),true);Map<String,Object> before=new LinkedHashMap<>(task);long version=((Number)task.get("version")).longValue()+1;List<Object> comments=new ArrayList<>();if(task.get("comments") instanceof List<?> list)comments.addAll(list);Map<String,Object> comment=Map.of("id",id(),"userId",principal.userId(),"name",principal.name(),"body",required(body,"body"),"createdAt",Instant.now().toString());comments.add(comment);task.put("comments",comments);task.remove("id");task.remove("version");db.update("UPDATE business_records SET payload=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND kind='tasks' AND id=?",encode(task),principal.tenantId(),id);change(principal,"tasks",id,version,"COMMENT",before,task);mentions.fromTaskComment(principal,id,comment.get("id").toString(),comment.get("body").toString(),body.get("mentionUserIds"));audit.log(principal,"COMMENT","tasks",id);return comment; }
}
