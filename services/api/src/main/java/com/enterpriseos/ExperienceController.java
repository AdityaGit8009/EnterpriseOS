package com.enterpriseos;

import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/org")
public class ExperienceController {
    private final AutomationSupport s;
    private final ReportService reports;
    private final FeatureFlagService flags;
    private final CustomAccessPolicy custom;

    public ExperienceController(AutomationSupport s, ReportService reports, FeatureFlagService flags,
            CustomAccessPolicy custom) {
        this.s = s;
        this.reports = reports;
        this.flags = flags;
        this.custom = custom;
    }

    @GetMapping("/dashboard/widgets")
    public Map<String, Object> widgets(@RequestAttribute Principal principal) {
        s.org(principal);
        var rows = s.db.queryForList(
                "SELECT widgets,version FROM dashboard_preferences WHERE tenant_id=? AND user_id=?",
                principal.tenantId(), principal.userId());
        return rows.isEmpty() ? Map.of("widgets", List.of(), "version", 0)
                : Map.of("widgets", decode(rows.getFirst().get("widgets")), "version", rows.getFirst().get("version"));
    }

    @PutMapping("/dashboard/widgets")
    @Transactional
    public Map<String, Object> widgets(@RequestAttribute Principal principal, @RequestBody Map<String, Object> body) {
        s.org(principal);
        s.lock(principal, "REPORTS", false);
        var raw = s.mapList(body.get("widgets"));
        if (raw.size() > 12)
            throw new ApiException(400, "Choose at most 12 dashboard widgets.");
        List<Map<String, Object>> output = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (var widget : raw) {
            String id = Inputs.text(widget, "id", 50), title = Inputs.text(widget, "title", 80),
                    type = Inputs.text(widget, "type", 10), source = Inputs.text(widget, "source", 30);
            if (!id.matches("[A-Za-z0-9_-]{1,50}") || !ids.add(id))
                throw new ApiException(400, "Widget IDs must be unique simple identifiers.");
            if (!Set.of("METRIC", "CHART", "LIST").contains(type))
                throw new ApiException(400, "Widget type must be METRIC, CHART or LIST.");
            if (!Set.of("employees", "projects", "attendance", "leaves", "invoices", "inventory").contains(source))
                throw new ApiException(400, "Choose a supported widget data source.");
            reports.authorize(principal, source);
            output.add(Map.of("id", id, "title", title, "type", type, "source", source, "order",
                    s.integer(widget, "order", output.size(), 0, 100)));
        }
        var old = s.db.queryForList(
                "SELECT version FROM dashboard_preferences WHERE tenant_id=? AND user_id=? FOR UPDATE",
                principal.tenantId(), principal.userId());
        if (body.get("version") != null && s.integer(body, "version", 0, 0,
                Integer.MAX_VALUE) != (old.isEmpty() ? 0 : ((Number) old.getFirst().get("version")).intValue()))
            throw new ApiException(409, "Dashboard changed. Reload before saving.");
        s.db.update(
                "INSERT INTO dashboard_preferences(tenant_id,user_id,widgets) VALUES (?,?,?) ON DUPLICATE KEY UPDATE widgets=VALUES(widgets),version=version+1,updated_at=UTC_TIMESTAMP(6)",
                principal.tenantId(), principal.userId(), s.encode(output));
        return widgets(principal);
    }

    @GetMapping("/dashboard/widgets/data")
    public List<Map<String, Object>> widgetData(@RequestAttribute Principal principal) {
        s.org(principal);
        var configured = s.mapList(widgets(principal).get("widgets"));
        return configured.stream().sorted(Comparator.comparingInt(w -> ((Number) w.get("order")).intValue()))
                .map(widget -> {
                    Map<String, Object> value = new LinkedHashMap<>(widget);
                    try {
                        List<Map<String, Object>> rows = reports.rows(principal, widget.get("source").toString(),
                                Map.of());
                        String type = widget.get("type").toString();
                        if (type.equals("METRIC"))
                            value.put("data", Map.of("count", rows.size()));
                        else if (type.equals("CHART")) {
                            Map<String, Integer> groups = new TreeMap<>();
                            for (var row : rows)
                                groups.merge(Objects.toString(row.get("status"), "Recorded"), 1, Integer::sum);
                            value.put("data", Map.of("series", groups.entrySet().stream()
                                    .map(e -> Map.of("label", e.getKey(), "value", e.getValue())).toList()));
                        } else
                            value.put("data", Map.of("rows", rows.stream().limit(8).map(row -> {
                                Map<String, Object> item = new LinkedHashMap<>();
                                for (String field : List.of("id", "name", "status", "employeeCode", "type", "days",
                                        "total", "currency"))
                                    if (row.get(field) != null)
                                        item.put(field, row.get(field));
                                return item;
                            }).toList()));
                    } catch (ApiException ex) {
                        value.put("error", ex.getMessage());
                        value.put("data", Map.of());
                    }
                    return value;
                }).toList();
    }

    @GetMapping("/feature-flags")
    public List<Map<String, Object>> flags(@RequestAttribute Principal principal) {
        s.org(principal);
        return EntitlementService.MODULES.stream().sorted().map(key -> {
            var rows = s.db.queryForList(
                    "SELECT enabled,description,version FROM feature_flags WHERE tenant_id=? AND flag_key=?",
                    principal.tenantId(), key);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("key", key);
            result.put("enabled", flags.enabled(principal.tenantId(), key));
            result.put("description", rows.isEmpty() ? "Enabled by default" : rows.getFirst().get("description"));
            result.put("version", rows.isEmpty() ? 0 : rows.getFirst().get("version"));
            return result;
        }).toList();
    }

    @PutMapping("/feature-flags/{key}")
    @Transactional
    public Map<String, Object> flag(@RequestAttribute Principal principal, @PathVariable String key,
            @RequestBody Map<String, Object> body) {
        s.allow(principal, "ADMIN");
        if (!EntitlementService.MODULES.contains(key))
            throw new ApiException(400, "Choose a supported module feature flag.");
        if (!(body.get("enabled") instanceof Boolean))
            throw new ApiException(400, "Feature enabled must be a boolean.");
        s.db.queryForObject("SELECT id FROM tenants WHERE id=? AND status='ACTIVE' FOR UPDATE", String.class,
                principal.tenantId());
        s.activeUser(principal.tenantId(), principal.userId());
        if (s.db.queryForObject(
                "SELECT COUNT(*) FROM memberships WHERE tenant_id=? AND user_id=? AND role='ADMIN' AND status='ACTIVE'",
                Integer.class, principal.tenantId(), principal.userId()) != 1)
            throw new ApiException(403, "Administrator access changed.");
        s.db.update(
                "INSERT INTO feature_flags(tenant_id,flag_key,enabled,description,actor_id) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),description=VALUES(description),actor_id=VALUES(actor_id),version=version+1,updated_at=UTC_TIMESTAMP(6)",
                principal.tenantId(), key, body.get("enabled"), Inputs.optional(body, "description", 500, ""),
                principal.userId());
        s.audit.log(principal, "FEATURE_FLAG_CHANGED", "featureFlag", key);
        return Map.of("key", key, "enabled", body.get("enabled"));
    }

    @GetMapping("/reports/deal-conversion")
    public Map<String, Object> conversions(@RequestAttribute Principal principal,
            @RequestParam(required = false) String startDate, @RequestParam(required = false) String endDate) {
        s.allow(principal, "ADMIN", "SALES", "MANAGER", "AUDITOR");
        flags.require(principal.tenantId(), "CRM");
        if (!custom.allowed(principal, "GET", "/api/v1/org/business/records/leads")
                || !custom.allowed(principal, "GET", "/api/v1/org/business/records/opportunities"))
            throw new ApiException(403, "Custom role cannot read CRM conversion sources.");
        String from = day(startDate), to = day(endDate);
        if (!from.isBlank() && !to.isBlank() && from.compareTo(to) > 0)
            throw new ApiException(400, "Conversion report dates are reversed.");
        java.time.LocalDateTime fromTime = from.isBlank() ? null : java.time.LocalDate.parse(from).atStartOfDay(),
                untilTime = to.isBlank() ? null : java.time.LocalDate.parse(to).plusDays(1).atStartOfDay();
        var leads = s.db.queryForList(
                "SELECT id,status,payload,created_by AS createdBy FROM business_entities WHERE tenant_id=? AND kind='leads' AND (? IS NULL OR created_at>=?) AND (? IS NULL OR created_at<?)",
                principal.tenantId(), fromTime, fromTime, untilTime, untilTime).stream()
                .filter(row -> crmVisible(principal, crmPayload(row))).toList();
        long converted = leads.stream().filter(row -> "CONVERTED".equals(row.get("status"))).count();
        var opportunities = s.db.queryForList(
                "SELECT id,payload,created_by AS createdBy FROM business_entities WHERE tenant_id=? AND kind='opportunities' AND (? IS NULL OR created_at>=?) AND (? IS NULL OR created_at<?)",
                principal.tenantId(), fromTime, fromTime, untilTime, untilTime);
        Map<String, Integer> outcomes = new TreeMap<>();
        Map<String, java.math.BigDecimal> won = new TreeMap<>();
        for (var row : opportunities) {
            var payload = crmPayload(row);
            if (!crmVisible(principal, payload))
                continue;
            String stage = Objects.toString(payload.get("stageId"), "");
            var stages = s.db.queryForList(
                    "SELECT payload FROM business_entities WHERE tenant_id=? AND id=? AND kind='pipelineStages'",
                    principal.tenantId(), stage);
            String outcome = stages.isEmpty() ? "OPEN"
                    : Objects.toString(s.decode(stages.getFirst().get("payload")).get("outcome"), "OPEN");
            outcomes.merge(outcome, 1, Integer::sum);
            if (outcome.equals("WON"))
                won.merge(Objects.toString(payload.get("currency"), "INR"),
                        s.decimal(payload, "amount", java.math.BigDecimal.ZERO), java.math.BigDecimal::add);
        }
        return Map.of("leadCount", leads.size(), "convertedLeadCount", converted, "leadConversionPercent",
                leads.isEmpty() ? 0 : 100.0 * converted / leads.size(), "opportunityOutcomes", outcomes,
                "wonAmountsByCurrency", won, "note",
                "Current outcomes for records created in the selected date range, not historical cohort transition timing.");
    }

    @PostMapping("/feature-flags/{key}")
    @Transactional
    public Map<String, Object> createFlag(@RequestAttribute Principal principal, @PathVariable String key,
            @RequestBody Map<String, Object> body) {
        return flag(principal, key, body);
    }

    private Map<String, Object> crmPayload(Map<String, Object> row) {
        var value = s.decode(row.get("payload"));
        value.put("createdBy", row.get("createdBy"));
        return value;
    }

    private boolean crmVisible(Principal p, Map<String, Object> row) {
        return !p.role().equals("SALES") || p.userId()
                .equals(row.get("ownerId") == null || row.get("ownerId").toString().isBlank() ? row.get("createdBy")
                        : row.get("ownerId"))
                || (row.get("teamIds") instanceof List<?> ids && ids.contains(p.userId()));
    }

    private String day(String value) {
        if (value == null || value.isBlank())
            return "";
        try {
            return java.time.LocalDate.parse(value).toString();
        } catch (Exception ex) {
            throw new ApiException(400, "Report dates must be ISO dates.");
        }
    }

    private List<?> decode(Object value) {
        try {
            return s.json.readValue(value.toString(), List.class);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
