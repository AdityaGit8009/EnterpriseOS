package com.enterpriseos;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Policy-driven workforce requests and project operations; no salary payment is
 * performed here.
 */
@RestController
@RequestMapping("/api/v1/org/workforce")
public class WorkforceExtensionController {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final OperationsController operations;
    private final EntitlementService entitlements;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final CustomAccessPolicy custom;

    public WorkforceExtensionController(JdbcTemplate db, ObjectMapper json, OperationsController operations,
            EntitlementService entitlements, AuditService audit, PlatformTransactionManager manager,
            CustomAccessPolicy custom) {
        this.db = db;
        this.json = json;
        this.operations = operations;
        this.entitlements = entitlements;
        this.audit = audit;
        this.tx = new TransactionTemplate(manager);
        this.custom = custom;
    }

    private ApiException fail(int status, String message) {
        return new ApiException(status, message);
    }

    private String id() {
        return UUID.randomUUID().toString();
    }

    private String text(Map<String, Object> m, String key) {
        return Objects.toString(m.get(key), "").trim();
    }

    private String need(Map<String, Object> m, String key) {
        String value = text(m, key);
        if (value.isBlank() || value.length() > 1000)
            throw fail(400, key + " is required and must not exceed 1000 characters");
        return value;
    }

    private BigDecimal num(Map<String, Object> m, String key, BigDecimal fallback) {
        try {
            return m.get(key) == null ? fallback : new BigDecimal(m.get(key).toString());
        } catch (Exception e) {
            throw fail(400, key + " must be a decimal number");
        }
    }

    private int integer(Map<String, Object> m, String key, int fallback, int minimum, int maximum) {
        BigDecimal value = num(m, key, BigDecimal.valueOf(fallback));
        try {
            int n = value.intValueExact();
            if (n < minimum || n > maximum)
                throw fail(400, key + " must be between " + minimum + " and " + maximum);
            return n;
        } catch (ArithmeticException e) {
            throw fail(400, key + " must be a whole number");
        }
    }

    private LocalDate date(String value, String key) {
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            throw fail(400, key + " must be an ISO date");
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw fail(400, "Invalid JSON payload");
        }
    }

    private Map<String, Object> decode(Object value) {
        try {
            return json.readValue(value.toString(), new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Invalid stored workforce extension", e);
        }
    }

    private Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
            result.put(pairs[i].toString(), pairs[i + 1]);
        return result;
    }

    private boolean role(Principal p, String... roles) {
        return Arrays.asList(roles).contains(p.role());
    }

    private void org(Principal p) {
        if (p.tenantId() == null || role(p, "PLATFORM_ADMIN"))
            throw fail(403, "Active organization membership is required");
    }

    private void hr(Principal p) {
        org(p);
        if (!role(p, "ADMIN", "HR"))
            throw fail(403, "Only HR or an organization administrator can perform this action");
    }

    private void member(Principal p) {
        org(p);
        if (role(p, "AUDITOR"))
            throw fail(403, "Auditors cannot submit workforce mutations");
    }

    private void lock(Principal p, String module, boolean creation) {
        org(p);
        String status = db.queryForObject("SELECT status FROM tenants WHERE id=? FOR UPDATE", String.class,
                p.tenantId());
        Integer active = db.queryForObject(
                "SELECT COUNT(*) FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.tenant_id=? AND m.user_id=? AND m.role=? AND m.status='ACTIVE' AND u.status='ACTIVE'",
                Integer.class, p.tenantId(), p.userId(), p.role());
        if (!"ACTIVE".equals(status) || active == null || active != 1)
            throw fail(403, "Organization membership or authority changed; sign in again");
        entitlements.require(p, module, creation);
    }

    private ZoneId zone(Principal p) {
        String zone = db.queryForObject("SELECT timezone FROM tenants WHERE id=?", String.class, p.tenantId());
        try {
            return ZoneId.of(zone);
        } catch (Exception e) {
            return ZoneOffset.UTC;
        }
    }

    private LocalDate today(Principal p) {
        return LocalDate.now(zone(p));
    }

    private Map<String, Object> stored(Principal p, String kind, String recordId) {
        var rows = db.queryForList("SELECT payload,version FROM business_records WHERE tenant_id=? AND kind=? AND id=?",
                p.tenantId(), kind, recordId);
        if (rows.isEmpty())
            throw fail(404, "Referenced workforce record was not found");
        Map<String, Object> result = decode(rows.getFirst().get("payload"));
        result.put("id", recordId);
        result.put("version", rows.getFirst().get("version"));
        return result;
    }

    private Map<String, Object> ownEmployee(Principal p) {
        return operations.list(p, "employees").stream().filter(e -> p.userId().equals(text(e, "userId"))).findFirst()
                .orElseThrow(() -> fail(409, "HR must link an employee profile to your active account first"));
    }

    private Principal activePrincipal(Principal actor, String userId) {
        var rows = db.queryForList(
                "SELECT u.id,u.name,u.email,m.role FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.tenant_id=? AND m.user_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'",
                actor.tenantId(), userId);
        if (rows.isEmpty())
            throw fail(409, "The employee no longer has an active organization membership");
        var r = rows.getFirst();
        return new Principal(userId, actor.tenantId(), r.get("name").toString(), r.get("email").toString(),
                r.get("role").toString());
    }

    private Map<String, Object> project(Principal p, String projectId, boolean write) {
        Map<String, Object> project = operations.list(p, "projects").stream()
                .filter(r -> projectId.equals(text(r, "id"))).findFirst()
                .orElseThrow(() -> fail(404, "Project is not visible in your organization"));
        if (write) {
            if ("ARCHIVED".equals(text(project, "status")))
                throw fail(409, "Archived projects are read-only");
            if (role(p, "AUDITOR") || !role(p, "ADMIN", "MANAGER") && !p.userId().equals(text(project, "ownerId")))
                throw fail(403, "Only project owner or manager can administer this project");
        }
        return project;
    }

    private void assignee(Principal p, Map<String, Object> project, String userId) {
        if (userId.isBlank())
            return;
        activePrincipal(p, userId);
        boolean belongs = userId.equals(text(project, "ownerId"))
                || project.get("memberIds") instanceof List<?> members && members.contains(userId);
        if (!belongs)
            throw fail(400, "Owner/assignee must belong to this project");
        for (Map<String, Object> e : operations.list(p, "employees"))
            if (userId.equals(text(e, "userId")) && "OFFBOARDED".equals(text(e, "status")))
                throw fail(409, "Offboarded employees cannot receive assignments");
    }

    private void version(Map<String, Object> body, Object actual) {
        if (num(body, "version", BigDecimal.valueOf(-1)).compareTo(new BigDecimal(actual.toString())) != 0)
            throw fail(409, "Record changed; refresh before saving");
    }

    private void policyKind(String kind) {
        if (!Set.of("encashment", "compOff").contains(kind))
            throw fail(404, "Unknown workforce policy");
    }

    private Map<String, Object> policy(Principal p, String kind) {
        var rows = db.queryForList("SELECT id,payload,version FROM workforce_policies WHERE tenant_id=? AND kind=?",
                p.tenantId(), kind);
        if (rows.isEmpty())
            throw fail(409, "HR must configure the " + kind + " policy before requests can be submitted");
        Map<String, Object> result = decode(rows.getFirst().get("payload"));
        result.put("id", rows.getFirst().get("id"));
        result.put("version", rows.getFirst().get("version"));
        return result;
    }

    @GetMapping("/policies/{kind}")
    public List<Map<String, Object>> policies(@RequestAttribute Principal principal, @PathVariable String kind) {
        org(principal);
        policyKind(kind);
        var rows = db.queryForList("SELECT id,payload,version FROM workforce_policies WHERE tenant_id=? AND kind=?",
                principal.tenantId(), kind);
        return rows.stream().map(r -> {
            Map<String, Object> m = decode(r.get("payload"));
            m.put("id", r.get("id"));
            m.put("version", r.get("version"));
            return m;
        }).toList();
    }

    @PostMapping("/policies/{kind}")
    @Transactional
    public Map<String, Object> createPolicy(@RequestAttribute Principal principal, @PathVariable String kind,
            @RequestBody Map<String, Object> body) {
        return savePolicy(principal, kind, null, body);
    }

    @PutMapping("/policies/{kind}/{id}")
    @Transactional
    public Map<String, Object> updatePolicy(@RequestAttribute Principal principal, @PathVariable String kind,
            @PathVariable String id, @RequestBody Map<String, Object> body) {
        return savePolicy(principal, kind, id, body);
    }

    private Map<String, Object> savePolicy(Principal p, String kind, String policyId, Map<String, Object> body) {
        hr(p);
        policyKind(kind);
        lock(p, "HR", policyId == null);
        Map<String, Object> old = null;
        if (policyId != null) {
            old = policy(p, kind);
            if (!policyId.equals(text(old, "id")))
                throw fail(404, "Workforce policy was not found");
            version(body, old.get("version"));
        } else if (!policies(p, kind).isEmpty())
            throw fail(409, "This policy already exists; update its current version");
        Map<String, Object> m = new LinkedHashMap<>();
        if (old != null)
            m.putAll(old);
        m.putAll(body);
        need(m, "name");
        String leaveType = need(m, "leaveType");
        if (operations.list(p, "leavePolicies").stream().noneMatch(
                l -> leaveType.equalsIgnoreCase(text(l, "type")) || leaveType.equalsIgnoreCase(text(l, "name"))))
            throw fail(400, "Policy leaveType must reference an existing leave policy");
        String canonical = operations.list(p, "leavePolicies").stream()
                .filter(l -> leaveType.equalsIgnoreCase(text(l, "type")) || leaveType.equalsIgnoreCase(text(l, "name")))
                .map(l -> text(l, "type")).findFirst().orElseThrow();
        m.put("leaveType", canonical);
        m.put("eligibleAfterMonths", integer(m, "eligibleAfterMonths", 0, 0, 600));
        m.put("maxDays", num(m, "maxDays", BigDecimal.valueOf(30)));
        if (num(m, "maxDays", BigDecimal.ZERO).signum() <= 0
                || num(m, "maxDays", BigDecimal.ZERO).compareTo(BigDecimal.valueOf(366)) > 0)
            throw fail(400, "maxDays must be greater than0 and at most366");
        if (kind.equals("encashment")) {
            BigDecimal remain = num(m, "minRemainingDays", BigDecimal.ZERO);
            if (remain.signum() < 0)
                throw fail(400, "minRemainingDays cannot be negative");
            m.put("minRemainingDays", remain);
        } else {
            m.put("hoursPerDay", integer(m, "hoursPerDay", 8, 1, 24));
            m.put("minimumHours", integer(m, "minimumHours", 1, 1, 24));
            m.put("maximumHours", integer(m, "maximumHours", 24, 1, 24));
            m.put("validForDays", integer(m, "validForDays", 90, 1, 366));
            if (((Number) m.get("minimumHours")).intValue() > ((Number) m.get("maximumHours")).intValue())
                throw fail(400, "Minimum hours exceeds maximum");
            m.putIfAbsent("requireAttendance", true);
            m.putIfAbsent("nonWorkingOnly", true);
            if (!(m.get("requireAttendance") instanceof Boolean) || !(m.get("nonWorkingOnly") instanceof Boolean))
                throw fail(400, "Policy flags must be booleans");
        }
        m.remove("id");
        m.remove("version");
        m.remove("tenantId");
        String id = policyId == null ? id() : policyId;
        if (old == null)
            db.update("INSERT INTO workforce_policies(id,tenant_id,kind,payload,updated_by) VALUES(?,?,?,?,?)", id,
                    p.tenantId(), kind, encode(m), p.userId());
        else
            db.update(
                    "UPDATE workforce_policies SET payload=?,version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND id=?",
                    encode(m), p.userId(), p.tenantId(), id);
        audit.log(p, "WORKFORCE_POLICY_UPDATED", kind, id);
        return policy(p, kind);
    }

    private void requestKind(String kind) {
        if (!Set.of("exits", "encashments", "compOff").contains(kind))
            throw fail(404, "Unknown workforce request type");
    }

    private Map<String, Object> requestRow(Map<String, Object> row) {
        Map<String, Object> m = decode(row.get("payload"));
        m.put("id", row.get("id"));
        m.put("kind", row.get("kind"));
        m.put("userId", row.get("user_id"));
        m.put("employeeId", row.get("employee_id"));
        m.put("status", row.get("status"));
        m.put("reviewedBy", row.get("reviewed_by"));
        m.put("createdAt", row.get("created_at"));
        m.put("completedAt", row.get("completed_at"));
        return m;
    }

    private Map<String, Object> request(Principal p, String requestId) {
        var rows = db.queryForList("SELECT * FROM workforce_requests WHERE tenant_id=? AND id=?", p.tenantId(),
                requestId);
        if (rows.isEmpty())
            throw fail(404, "Workforce request not found");
        return requestRow(rows.getFirst());
    }

    @GetMapping("/requests/{kind}")
    public List<Map<String, Object>> requests(@RequestAttribute Principal principal, @PathVariable String kind) {
        org(principal);
        requestKind(kind);
        return db
                .queryForList("SELECT * FROM workforce_requests WHERE tenant_id=? AND kind=? ORDER BY created_at DESC",
                        principal.tenantId(), kind)
                .stream().map(this::requestRow)
                .filter(r -> role(principal, "ADMIN", "HR", "AUDITOR") || principal.userId().equals(text(r, "userId")))
                .map(r -> {
                    Map<String, Object> employee = stored(principal, "employees", text(r, "employeeId"));
                    r.put("name", employee.get("name"));
                    return r;
                }).toList();
    }

    private void eligibility(Principal actor, Map<String, Object> employee, Map<String, Object> policy) {
        LocalDate joined = date(need(employee, "joinDate"), "Employee joinDate");
        if (joined.plusMonths(num(policy, "eligibleAfterMonths", BigDecimal.ZERO).longValue()).isAfter(today(actor)))
            throw fail(409, "Employee has not completed the configured eligibility period");
        if ("OFFBOARDED".equals(text(employee, "status")))
            throw fail(409, "Offboarded employees cannot submit new benefit requests");
    }

    private BigDecimal balance(Principal actor, String user, String leaveType) {
        Principal subject = activePrincipal(actor, user);
        return operations.balances(subject).stream().filter(b -> leaveType.equalsIgnoreCase(text(b, "type")))
                .map(b -> num(b, "remaining", BigDecimal.ZERO)).findFirst()
                .orElseThrow(() -> fail(409, "The snapshotted leave policy is not available for the current year"));
    }

    private BigDecimal encashmentEligible(Principal actor, String user, Map<String, Object> snapshot) {
        return balance(actor, user, text(snapshot, "leaveType"))
                .subtract(num(snapshot, "minRemainingDays", BigDecimal.ZERO)).max(BigDecimal.ZERO)
                .min(num(snapshot, "maxDays", BigDecimal.ZERO));
    }

    @PostMapping("/requests/{kind}")
    @Transactional
    public Map<String, Object> submit(@RequestAttribute Principal principal, @PathVariable String kind,
            @RequestBody Map<String, Object> body) {
        member(principal);
        requestKind(kind);
        lock(principal, "HR", true);
        Map<String, Object> employee = ownEmployee(principal), m = new LinkedHashMap<>();
        m.put("reason", need(body, "reason"));
        m.put("employeeName", employee.get("name"));
        if (kind.equals("exits")) {
            LocalDate exit = date(need(body, "exitDate"), "exitDate");
            if (!text(employee, "joinDate").isEmpty() && exit.isBefore(date(text(employee, "joinDate"), "joinDate")))
                throw fail(400, "Exit date precedes employment");
            Integer pending = db.queryForObject(
                    "SELECT COUNT(*) FROM workforce_requests WHERE tenant_id=? AND user_id=? AND kind='exits' AND status IN ('PENDING','APPROVED')",
                    Integer.class, principal.tenantId(), principal.userId());
            if (pending != null && pending > 0)
                throw fail(409, "An exit request is already pending or approved");
            m.put("exitDate", exit.toString());
        } else {
            String policyKind = kind.equals("encashments") ? "encashment" : "compOff";
            Map<String, Object> snapshot = policy(principal, policyKind);
            eligibility(principal, employee, snapshot);
            m.put("policySnapshot", snapshot);
            if (kind.equals("encashments")) {
                BigDecimal days = num(body, "days", BigDecimal.ZERO);
                if (days.scale() > 2 || days.signum() <= 0
                        || days.compareTo(encashmentEligible(principal, principal.userId(), snapshot)) > 0)
                    throw fail(409, "Requested days exceed current encashment eligibility after minimum balance");
                m.put("days", days);
                m.put("eligibilityOnly", true);
            } else {
                LocalDate workDate = date(need(body, "workDate"), "workDate"), today = today(principal);
                if (workDate.isAfter(today) || workDate
                        .isBefore(today.minusDays(num(snapshot, "validForDays", BigDecimal.valueOf(90)).longValue())))
                    throw fail(400, "Comp-off work date is outside the policy submission window");
                if (Boolean.TRUE.equals(snapshot.get("nonWorkingOnly")) && workDate.getDayOfWeek().getValue() < 6)
                    throw fail(409,
                            "This policy requires work on a weekend; configure nonWorkingOnly=false for another approved rule");
                BigDecimal hours = num(body, "hours", BigDecimal.ZERO);
                if (hours.compareTo(num(snapshot, "minimumHours", BigDecimal.ONE)) < 0
                        || hours.compareTo(num(snapshot, "maximumHours", BigDecimal.valueOf(24))) > 0)
                    throw fail(400, "Hours are outside the policy minimum/maximum");
                BigDecimal days = hours.divide(num(snapshot, "hoursPerDay", BigDecimal.valueOf(8)), 2,
                        RoundingMode.DOWN);
                if (days.signum() <= 0 || days.compareTo(num(snapshot, "maxDays", BigDecimal.valueOf(30))) > 0)
                    throw fail(400, "Earned days exceed policy cap");
                BigDecimal claimed = db.queryForObject(
                        "SELECT COALESCE(SUM(CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.hours')) AS DECIMAL(10,2))),0) FROM workforce_requests WHERE tenant_id=? AND user_id=? AND kind='compOff' AND status IN ('PENDING','APPROVED') AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.workDate'))=?",
                        BigDecimal.class, principal.tenantId(), principal.userId(), workDate.toString());
                if (Boolean.TRUE.equals(snapshot.get("requireAttendance"))) {
                    long minutes = 0;
                    for (Map<String, Object> attendance : db.queryForList(
                            "SELECT in_at,out_at FROM attendance WHERE tenant_id=? AND user_id=? AND work_date=? AND out_at IS NOT NULL",
                            principal.tenantId(), principal.userId(), workDate))
                        minutes += Math.max(0, Duration.between(Instant.parse(attendance.get("in_at").toString()),
                                Instant.parse(attendance.get("out_at").toString())).toMinutes());
                    if (BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 4, RoundingMode.DOWN)
                            .compareTo(hours.add(claimed)) < 0)
                        throw fail(409, "Claimed comp-off exceeds recorded completed attendance hours");
                } else if (claimed.signum() > 0)
                    throw fail(409, "A comp-off request already covers this work date");
                m.put("workDate", workDate.toString());
                m.put("hours", hours);
                m.put("days", days);
            }
        }
        String id = id();
        db.update("INSERT INTO workforce_requests(id,tenant_id,kind,user_id,employee_id,payload) VALUES(?,?,?,?,?,?)",
                id, principal.tenantId(), kind, principal.userId(), employee.get("id"), encode(m));
        audit.log(principal, "WORKFORCE_REQUEST_SUBMITTED", kind, id);
        return request(principal, id);
    }

    @PostMapping("/requests/{id}/decision")
    @Transactional
    public Map<String, Object> decide(@RequestAttribute Principal principal, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        hr(principal);
        lock(principal, "HR", false);
        Map<String, Object> r = request(principal, id);
        if (!"PENDING".equals(text(r, "status")))
            throw fail(409, "Request has already been reviewed");
        if (principal.userId().equals(text(r, "userId")))
            throw fail(403, "You cannot review your own workforce request");
        String decision = need(body, "decision");
        if (!Set.of("APPROVED", "REJECTED").contains(decision))
            throw fail(400, "Decision must be APPROVED or REJECTED");
        String state = decision;
        if (decision.equals("APPROVED") && !text(r, "kind").equals("exits")) {
            Map<String, Object> snapshot = decode(encode(r.get("policySnapshot")));
            eligibility(principal, stored(principal, "employees", text(r, "employeeId")), snapshot);
            activePrincipal(principal, text(r, "userId"));
            if (text(r, "kind").equals("encashments")) {
                BigDecimal eligible = encashmentEligible(principal, text(r, "userId"), snapshot);
                if (num(r, "days", BigDecimal.ZERO).compareTo(eligible) > 0)
                    throw fail(409, "Balance changed; requested encashment is no longer eligible");
                r.put("eligibleDaysAtReview", eligible);
                r.put("eligibilityOnly", true);
                state = "ELIGIBLE";
            } else {
                validateCompOffAttendance(principal, r, snapshot);
                String adjustment = id();
                BigDecimal days = num(r, "days", BigDecimal.ZERO);
                String reason = "Approved compensatory off: " + text(r, "reason");
                if (reason.length() > 1000)
                    reason = reason.substring(0, 1000);
                db.update(
                        "INSERT INTO leave_adjustments(id,tenant_id,user_id,leave_type,entitlement_year,delta,reason,actor_id) VALUES(?,?,?,?,?,?,?,?)",
                        adjustment, principal.tenantId(), r.get("userId"), snapshot.get("leaveType"),
                        today(principal).getYear(), days, reason, principal.userId());
                db.update(
                        "INSERT INTO workforce_credit_postings(request_id,tenant_id,adjustment_id,days) VALUES(?,?,?,?)",
                        id, principal.tenantId(), adjustment, days);
                r.put("adjustmentId", adjustment);
            }
        }
        r.put("reviewReason", text(body, "reason"));
        r.put("reviewedAt", Instant.now().toString());
        db.update(
                "UPDATE workforce_requests SET payload=?,status=?,reviewed_by=?,reviewed_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND id=?",
                encode(r), state, principal.userId(), principal.tenantId(), id);
        audit.log(principal, "WORKFORCE_REQUEST_" + state, text(r, "kind"), id);
        if (decision.equals("APPROVED") && text(r, "kind").equals("exits")
                && !date(text(r, "exitDate"), "exitDate").isAfter(today(principal)))
            return completeExit(principal, id);
        return request(principal, id);
    }

    private void validateCompOffAttendance(Principal actor, Map<String, Object> request, Map<String, Object> snapshot) {
        if (!Boolean.TRUE.equals(snapshot.get("requireAttendance")))
            return;
        String user = text(request, "userId"), workDate = text(request, "workDate");
        BigDecimal claimed = db.queryForObject(
                "SELECT COALESCE(SUM(CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.hours')) AS DECIMAL(10,2))),0) FROM workforce_requests WHERE tenant_id=? AND user_id=? AND kind='compOff' AND status IN ('PENDING','APPROVED') AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.workDate'))=?",
                BigDecimal.class, actor.tenantId(), user, workDate);
        long minutes = 0;
        for (Map<String, Object> attendance : db.queryForList(
                "SELECT in_at,out_at FROM attendance WHERE tenant_id=? AND user_id=? AND work_date=? AND out_at IS NOT NULL",
                actor.tenantId(), user, date(workDate, "workDate")))
            minutes += Math.max(0, Duration.between(Instant.parse(attendance.get("in_at").toString()),
                    Instant.parse(attendance.get("out_at").toString())).toMinutes());
        if (BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 4, RoundingMode.DOWN).compareTo(claimed) < 0)
            throw fail(409, "Recorded attendance changed and no longer supports pending/approved comp-off hours");
    }

    @PostMapping("/requests/{id}/complete")
    @Transactional
    public Map<String, Object> completeExit(@RequestAttribute Principal principal, @PathVariable String id) {
        hr(principal);
        lock(principal, "HR", false);
        Map<String, Object> r = request(principal, id);
        if (!text(r, "kind").equals("exits") || !text(r, "status").equals("APPROVED"))
            throw fail(409, "Only an approved due exit can be completed");
        if (principal.userId().equals(text(r, "userId")))
            throw fail(403, "Another HR reviewer must complete your own exit");
        if (date(text(r, "exitDate"), "exitDate").isAfter(today(principal)))
            throw fail(409, "Exit is not due yet");
        Map<String, Object> employee = stored(principal, "employees", text(r, "employeeId"));
        if (!"OFFBOARDED".equals(text(employee, "status")))
            operations.update(principal, "employees", text(r, "employeeId"), map("version", employee.get("version"),
                    "status", "OFFBOARDED", "effectiveDate", r.get("exitDate")));
        db.update(
                "UPDATE workforce_requests SET status='COMPLETED',completed_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND id=?",
                principal.tenantId(), id);
        audit.log(principal, "EMPLOYEE_EXIT_COMPLETED", "employees", text(r, "employeeId"));
        return request(principal, id);
    }

    @GetMapping("/projects/{projectId}/risks")
    public List<Map<String, Object>> risks(@RequestAttribute Principal principal, @PathVariable String projectId) {
        project(principal, projectId, false);
        return db.queryForList(
                "SELECT id,payload,version FROM workforce_risks WHERE tenant_id=? AND project_id=? ORDER BY created_at DESC",
                principal.tenantId(), projectId).stream().map(r -> {
                    Map<String, Object> m = decode(r.get("payload"));
                    m.put("id", r.get("id"));
                    m.put("version", r.get("version"));
                    m.put("projectId", projectId);
                    return m;
                }).toList();
    }

    @PostMapping("/projects/{projectId}/risks")
    @Transactional
    public Map<String, Object> createRisk(@RequestAttribute Principal principal, @PathVariable String projectId,
            @RequestBody Map<String, Object> body) {
        lock(principal, "PROJECTS", true);
        return saveRisk(principal, projectId, null, body);
    }

    @PutMapping("/risks/{id}")
    @Transactional
    public Map<String, Object> updateRisk(@RequestAttribute Principal principal, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        lock(principal, "PROJECTS", false);
        var rows = db.queryForList("SELECT project_id FROM workforce_risks WHERE tenant_id=? AND id=?",
                principal.tenantId(), id);
        if (rows.isEmpty())
            throw fail(404, "Project risk not found");
        return saveRisk(principal, rows.getFirst().get("project_id").toString(), id, body);
    }

    private Map<String, Object> saveRisk(Principal p, String projectId, String riskId, Map<String, Object> body) {
        Map<String, Object> proj = project(p, projectId, true), m = new LinkedHashMap<>();
        if (riskId != null) {
            Map<String, Object> old = risks(p, projectId).stream().filter(r -> riskId.equals(text(r, "id"))).findFirst()
                    .orElseThrow();
            version(body, old.get("version"));
            m.putAll(old);
        }
        m.putAll(body);
        need(m, "name");
        int probability = integer(m, "probability", 1, 1, 5), impact = integer(m, "impact", 1, 1, 5);
        m.put("probability", probability);
        m.put("impact", impact);
        m.put("score", probability * impact);
        String status = text(m, "status");
        if (status.isEmpty())
            m.put("status", "OPEN");
        else if (!Set.of("OPEN", "MITIGATING", "CLOSED").contains(status))
            throw fail(400, "Invalid risk status");
        assignee(p, proj, text(m, "ownerId"));
        m.remove("id");
        m.remove("version");
        m.put("projectId", projectId);
        String id = riskId == null ? id() : riskId;
        if (riskId == null)
            db.update("INSERT INTO workforce_risks(id,tenant_id,project_id,payload,created_by) VALUES(?,?,?,?,?)", id,
                    p.tenantId(), projectId, encode(m), p.userId());
        else
            db.update(
                    "UPDATE workforce_risks SET payload=?,version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND id=?",
                    encode(m), p.tenantId(), id);
        audit.log(p, "PROJECT_RISK_UPDATED", "workforce_risks", id);
        return risks(p, projectId).stream().filter(r -> id.equals(text(r, "id"))).findFirst().orElseThrow();
    }

    private Map<String, Object> recurringRow(Map<String, Object> r) {
        Map<String, Object> m = decode(r.get("payload"));
        m.put("id", r.get("id"));
        m.put("projectId", r.get("project_id"));
        m.put("nextDate", r.get("next_date").toString());
        m.put("enabled", r.get("enabled"));
        m.put("version", r.get("version"));
        m.put("lastError", r.get("last_error"));
        return m;
    }

    private Map<String, Object> recurring(Principal p, String templateId) {
        var rows = db.queryForList("SELECT * FROM recurring_task_templates WHERE tenant_id=? AND id=?", p.tenantId(),
                templateId);
        if (rows.isEmpty())
            throw fail(404, "Recurring template not found");
        return recurringRow(rows.getFirst());
    }

    @GetMapping("/recurring-tasks")
    public List<Map<String, Object>> recurringTasks(@RequestAttribute Principal principal) {
        org(principal);
        Set<String> visible = new HashSet<>();
        operations.list(principal, "projects").forEach(r -> visible.add(text(r, "id")));
        return db
                .queryForList("SELECT * FROM recurring_task_templates WHERE tenant_id=? ORDER BY next_date,id",
                        principal.tenantId())
                .stream().map(this::recurringRow).filter(r -> visible.contains(text(r, "projectId"))).toList();
    }

    @PostMapping("/recurring-tasks")
    @Transactional
    public Map<String, Object> createRecurring(@RequestAttribute Principal principal,
            @RequestBody Map<String, Object> body) {
        return saveRecurring(principal, null, body);
    }

    @PutMapping("/recurring-tasks/{id}")
    @Transactional
    public Map<String, Object> updateRecurring(@RequestAttribute Principal principal, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return saveRecurring(principal, id, body);
    }

    private Map<String, Object> saveRecurring(Principal p, String templateId, Map<String, Object> body) {
        lock(p, "PROJECTS", templateId == null);
        Map<String, Object> m = new LinkedHashMap<>();
        if (templateId != null) {
            Map<String, Object> old = recurring(p, templateId);
            version(body, old.get("version"));
            project(p, text(old, "projectId"), true);
            m.putAll(old);
            if (body.containsKey("projectId") && !text(old, "projectId").equals(text(body, "projectId")))
                throw fail(409, "A recurring template cannot move between projects");
        }
        m.putAll(body);
        String projectId = need(m, "projectId");
        Map<String, Object> proj = project(p, projectId, true);
        need(m, "name");
        assignee(p, proj, text(m, "assigneeId"));
        m.put("intervalDays", integer(m, "intervalDays", 7, 1, 366));
        m.put("dueAfterDays", integer(m, "dueAfterDays", 0, 0, 366));
        LocalDate next = date(need(m, "nextDate"), "nextDate");
        if (!text(m, "endDate").isEmpty() && date(text(m, "endDate"), "endDate").isBefore(next))
            throw fail(400, "Recurring endDate precedes nextDate");
        String priority = text(m, "priority");
        if (priority.isEmpty())
            m.put("priority", "MEDIUM");
        else if (!Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(priority))
            throw fail(400, "Invalid recurring task priority");
        if (m.containsKey("enabled") && !(m.get("enabled") instanceof Boolean))
            throw fail(400, "enabled must be a boolean");
        boolean enabled = !Boolean.FALSE.equals(m.get("enabled"));
        m.remove("id");
        m.remove("version");
        m.remove("lastError");
        String id = templateId == null ? id() : templateId;
        if (templateId == null)
            db.update(
                    "INSERT INTO recurring_task_templates(id,tenant_id,project_id,payload,next_date,enabled,created_by) VALUES(?,?,?,?,?,?,?)",
                    id, p.tenantId(), projectId, encode(m), next, enabled, p.userId());
        else
            db.update(
                    "UPDATE recurring_task_templates SET project_id=?,payload=?,next_date=?,enabled=?,version=version+1,last_error=NULL,updated_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND id=?",
                    projectId, encode(m), next, enabled, p.tenantId(), id);
        audit.log(p, "RECURRING_TASK_CONFIGURED", "recurring_task_templates", id);
        return recurring(p, id);
    }

    @GetMapping("/recurring-tasks/{id}/occurrences")
    public List<Map<String, Object>> occurrences(@RequestAttribute Principal principal, @PathVariable String id) {
        Map<String, Object> template = recurring(principal, id);
        project(principal, text(template, "projectId"), false);
        return db.queryForList(
                "SELECT id,occurrence_date AS occurrenceDate,task_id AS taskId,created_at AS createdAt FROM recurring_task_occurrences WHERE tenant_id=? AND template_id=? ORDER BY occurrence_date",
                principal.tenantId(), id);
    }

    @PostMapping("/recurring-tasks/run-due")
    public Map<String, Object> runDue(@RequestAttribute Principal principal) {
        member(principal);
        if (!role(principal, "ADMIN", "MANAGER") && operations.list(principal, "projects").stream()
                .noneMatch(p -> principal.userId().equals(text(p, "ownerId"))))
            throw fail(403, "Project manager or owner permission is required");
        List<Map<String, Object>> candidates = recurringTasks(principal).stream()
                .filter(r -> Boolean.TRUE.equals(r.get("enabled"))
                        && !date(text(r, "nextDate"), "nextDate").isAfter(today(principal)))
                .toList();
        List<Map<String, Object>> result = new ArrayList<>();
        int generated = 0;
        for (Map<String, Object> candidate : candidates) {
            if (generated >= 100)
                break;
            if (!role(principal, "ADMIN", "MANAGER") && !principal.userId()
                    .equals(text(project(principal, text(candidate, "projectId"), false), "ownerId")))
                continue;
            try {
                int remaining = 100 - generated;
                Map<String, Object> item = tx.execute(s -> generate(principal, text(candidate, "id"), remaining));
                if (item != null) {
                    generated += ((Number) item.get("generated")).intValue();
                    result.add(item);
                }
            } catch (ApiException e) {
                if (e.status() == 403)
                    throw e;
                result.add(map("id", candidate.get("id"), "generated", 0, "error", e.getMessage()));
                tx.executeWithoutResult(state -> {
                    lock(principal, "PROJECTS", false);
                    requireRecurringGrant(principal);
                    db.update("UPDATE recurring_task_templates SET last_error=? WHERE tenant_id=? AND id=?",
                            e.getMessage().substring(0, Math.min(1000, e.getMessage().length())), principal.tenantId(),
                            candidate.get("id"));
                });
            }
        }
        return map("generated", generated, "templates", result);
    }

    private void requireRecurringGrant(Principal actor) {
        if (!custom.allowed(actor, "POST", "/api/v1/org/workforce/recurring-tasks/run-due"))
            throw fail(403, "Your custom role no longer permits recurring task generation");
    }

    private Map<String, Object> generate(Principal actor, String templateId, int maximum) {
        lock(actor, "PROJECTS", false);
        requireRecurringGrant(actor);
        Map<String, Object> template = recurring(actor, templateId);
        project(actor, text(template, "projectId"), true);
        if (!Boolean.TRUE.equals(template.get("enabled")))
            return map("id", templateId, "generated", 0);
        LocalDate occurrence = date(text(template, "nextDate"), "nextDate"),
                end = text(template, "endDate").isEmpty() ? LocalDate.MAX : date(text(template, "endDate"), "endDate");
        int generated = 0;
        while (!occurrence.isAfter(today(actor)) && !occurrence.isAfter(end) && generated < maximum) {
            Integer existing = db.queryForObject(
                    "SELECT COUNT(*) FROM recurring_task_occurrences WHERE tenant_id=? AND template_id=? AND occurrence_date=?",
                    Integer.class, actor.tenantId(), templateId, occurrence);
            if (existing == null || existing == 0) {
                Map<String, Object> body = map("name", text(template, "name") + " · " + occurrence, "projectId",
                        template.get("projectId"), "priority", template.get("priority"), "status", "TODO", "startDate",
                        occurrence.toString(), "dueDate",
                        occurrence.plusDays(num(template, "dueAfterDays", BigDecimal.ZERO).longValue()).toString(),
                        "recurringTemplateId", templateId, "occurrenceDate", occurrence.toString());
                if (!text(template, "assigneeId").isEmpty())
                    body.put("assigneeId", template.get("assigneeId"));
                Map<String, Object> task = operations.create(actor, "tasks", body);
                db.update(
                        "INSERT INTO recurring_task_occurrences(id,tenant_id,template_id,occurrence_date,task_id) VALUES(?,?,?,?,?)",
                        id(), actor.tenantId(), templateId, occurrence, task.get("id"));
                generated++;
            }
            occurrence = occurrence.plusDays(num(template, "intervalDays", BigDecimal.valueOf(7)).longValue());
        }
        db.update(
                "UPDATE recurring_task_templates SET next_date=?,enabled=?,version=version+1,last_error=NULL,updated_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND id=?",
                occurrence, !occurrence.isAfter(end), actor.tenantId(), templateId);
        audit.log(actor, "RECURRING_TASKS_GENERATED", "recurring_task_templates", templateId);
        return map("id", templateId, "generated", generated, "nextDate", occurrence.toString());
    }

    @GetMapping("/analytics/tasks")
    public Map<String, Object> taskAnalytics(@RequestAttribute Principal principal) {
        org(principal);
        if (!custom.allowed(principal, "GET", "/api/v1/org/records/tasks"))
            throw fail(403, "Your custom role cannot read the task records required for analytics");
        LocalDate today = today(principal);
        List<Map<String, Object>> tasks = operations.list(principal, "tasks");
        Map<String, Integer> byStatus = new TreeMap<>(), aging = new LinkedHashMap<>(), throughput = new TreeMap<>();
        for (String key : List.of("0–7 days", "8–30 days", "31–90 days", "90+ days"))
            aging.put(key, 0);
        int overdue = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> task : tasks) {
            String status = text(task, "status");
            byStatus.merge(status, 1, Integer::sum);
            String taskId = text(task, "id");
            java.sql.Timestamp created = db.queryForObject(
                    "SELECT created_at FROM business_records WHERE tenant_id=? AND kind='tasks' AND id=?",
                    java.sql.Timestamp.class, principal.tenantId(), taskId);
            LocalDate start = created.toInstant().atZone(zone(principal)).toLocalDate();
            long age = Math.max(0, ChronoUnit.DAYS.between(start, today));
            boolean done = status.equals("DONE"), late = !done && !text(task, "dueDate").isEmpty()
                    && date(text(task, "dueDate"), "dueDate").isBefore(today);
            if (!done)
                aging.merge(age <= 7 ? "0–7 days" : age <= 30 ? "8–30 days" : age <= 90 ? "31–90 days" : "90+ days", 1,
                        Integer::sum);
            if (late)
                overdue++;
            String completion = "";
            if (task.get("history") instanceof List<?> history)
                for (Object item : history)
                    if (item instanceof Map<?, ?> h && "DONE".equals(h.get("status")))
                        completion = Objects.toString(h.get("at"), "");
            if (done && !completion.isEmpty()) {
                LocalDate completed = Instant.parse(completion).atZone(zone(principal)).toLocalDate();
                LocalDate week = completed.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                throughput.merge(week.toString(), 1, Integer::sum);
            }
            rows.add(map("id", taskId, "name", task.get("name"), "projectId", task.get("projectId"), "status", status,
                    "ageDays", age, "overdue", late, "completedAt", completion));
        }
        return map("total", tasks.size(), "overdue", overdue, "byStatus", byStatus, "aging", aging, "weeklyThroughput",
                throughput, "tasks", rows);
    }
}
