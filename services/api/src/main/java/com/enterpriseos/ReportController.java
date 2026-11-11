package com.enterpriseos;

import java.util.*;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/org/reports")
public class ReportController {
    private final AutomationSupport s;
    private final ReportService reports;

    public ReportController(AutomationSupport s, ReportService reports) {
        this.s = s;
        this.reports = reports;
    }

    @GetMapping
    public Map<String, Object> summary(@RequestAttribute Principal principal) {
        s.org(principal);
        var metrics = new LinkedHashMap<String, Object>();
        for (String kind : List.of("employees", "projects", "attendance", "leaves", "inventory", "invoices", "payroll"))
            try {
                metrics.put(kind, reports.rows(principal, kind, Map.of()).size());
            } catch (ApiException ex) {
                if (ex.status() != 403)
                    throw ex;
            }
        return Map.of("generatedAt", java.time.Instant.now().toString(), "metrics", metrics, "note",
                "Counts reflect current role and tenant access; financial and payroll figures require their domain permissions.");
    }

    @GetMapping("/definitions")
    public List<Map<String, Object>> definitions(@RequestAttribute Principal principal) {
        s.org(principal);
        return s.db.queryForList(
                "SELECT id,name,kind,filters,cadence_hours AS cadenceHours,next_run_at AS nextRunAt FROM report_definitions WHERE tenant_id=? AND owner_id=? ORDER BY created_at DESC",
                principal.tenantId(), principal.userId()).stream().map(row -> {
                    row.put("filters", s.decode(row.get("filters")));
                    return row;
                }).toList();
    }

    @PostMapping("/definitions")
    @Transactional
    public Map<String, Object> definition(@RequestAttribute Principal principal,
            @RequestBody Map<String, Object> body) {
        s.lock(principal, "REPORTS", false);
        String name = Inputs.text(body, "name", 180), kind = Inputs.text(body, "kind", 40);
        Map<String, Object> filters = filters(body);
        reports.rows(principal, kind, filters);
        int cadence = s.integer(body, "cadenceHours", 0, 0, 8760);
        String id = s.id();
        s.db.update(
                "INSERT INTO report_definitions(id,tenant_id,owner_id,name,kind,filters,cadence_hours,next_run_at) VALUES (?,?,?,?,?,?,?,?)",
                id, principal.tenantId(), principal.userId(), name, kind, s.encode(filters), cadence,
                cadence == 0 ? null : java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(cadence * 3600L)));
        s.audit.log(principal, "REPORT_DEFINITION_CREATED", "reportDefinition", id);
        return Map.of("id", id, "name", name, "kind", kind, "cadenceHours", cadence);
    }

    @PutMapping("/definitions/{id}")
    @Transactional
    public Map<String, Object> schedule(@RequestAttribute Principal principal, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        s.lock(principal, "REPORTS", false);
        var row = s
                .first(s.db.queryForList("SELECT * FROM report_definitions WHERE tenant_id=? AND owner_id=? AND id=?",
                        principal.tenantId(), principal.userId(), id), "Report definition");
        int cadence = s.integer(body, "cadenceHours", 0, 0, 8760);
        Map<String, Object> filters = body.containsKey("filters") ? filters(body) : s.decode(row.get("filters"));
        reports.rows(principal, row.get("kind").toString(), filters);
        s.db.update("UPDATE report_definitions SET cadence_hours=?,next_run_at=?,filters=? WHERE tenant_id=? AND id=?",
                cadence,
                cadence == 0 ? null : java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(cadence * 3600L)),
                s.encode(filters), principal.tenantId(), id);
        s.audit.log(principal, "REPORT_SCHEDULE_UPDATED", "reportDefinition", id);
        return Map.of("id", id, "cadenceHours", cadence);
    }

    @GetMapping("/exports")
    public List<Map<String, Object>> exports(@RequestAttribute Principal principal) {
        s.org(principal);
        return s.db.queryForList(
                "SELECT id,name,kind,row_count AS rowCount,status,error_message AS errorMessage,expires_at AS expiresAt,created_at AS createdAt FROM report_exports WHERE tenant_id=? AND owner_id=? ORDER BY created_at DESC LIMIT 200",
                principal.tenantId(), principal.userId());
    }

    @PostMapping("/exports")
    public Map<String, Object> export(@RequestAttribute Principal principal, @RequestBody Map<String, Object> body) {
        String kind = Inputs.text(body, "kind", 40), name = Inputs.optional(body, "name", 180, kind + " report");
        return reports.generate(principal, name, kind, filters(body), null);
    }

    @PostMapping("/definitions/{id}/exports")
    public Map<String, Object> exportSaved(@RequestAttribute Principal principal, @PathVariable String id) {
        var row = s
                .first(s.db.queryForList("SELECT * FROM report_definitions WHERE tenant_id=? AND owner_id=? AND id=?",
                        principal.tenantId(), principal.userId(), id), "Report definition");
        return reports.generate(principal, row.get("name").toString(), row.get("kind").toString(),
                s.decode(row.get("filters")), id);
    }

    @GetMapping("/exports/{id}/download")
    public ResponseEntity<byte[]> download(@RequestAttribute Principal principal, @PathVariable String id) {
        byte[] bytes = reports.content(principal, id);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=enterpriseos-report.csv").body(bytes);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> filters(Map<String, Object> body) {
        if (body.get("filters") == null)
            return Map.of();
        if (!(body.get("filters") instanceof Map<?, ?> map))
            throw new ApiException(400, "filters must be an object.");
        return (Map<String, Object>) map;
    }
}
