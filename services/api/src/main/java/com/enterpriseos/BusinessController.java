package com.enterpriseos;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/**
 * Commercial operations. All financial/stock transitions share the tenant
 * serialization lock.
 */
@RestController
@RequestMapping("/api/v1/org/business")
public class BusinessController {
    private static final Set<String> KINDS = Set.of("accounts", "contacts", "leads", "pipelineStages", "opportunities",
            "activities", "quotes", "items", "warehouses", "suppliers", "purchaseRequests", "purchaseOrders",
            "costCenters", "budgets", "expenses", "invoices", "compensations", "payrollRuns", "rfqs", "supplierQuotes",
            "supplierContracts", "stockReservations", "inventoryCounts", "stockAdjustments", "financialPeriods",
            "customerContracts", "supplierBills");
    private static final Set<String> DOCUMENTS = Set.of("quotes", "purchaseRequests", "purchaseOrders", "expenses",
            "invoices", "rfqs", "supplierQuotes", "supplierBills");
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AuditService audit;
    private final EntitlementService entitlements;
    private final MailService mail;
    private final String baseUrl;

    public BusinessController(JdbcTemplate db, ObjectMapper json, AuditService audit, EntitlementService entitlements,
            MailService mail, @Value("${app.public-base-url}") String baseUrl) {
        this.db = db;
        this.json = json;
        this.audit = audit;
        this.entitlements = entitlements;
        this.mail = mail;
        this.baseUrl = baseUrl;
    }

    private Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
            result.put(pairs[i].toString(), pairs[i + 1]);
        return result;
    }

    private String id() {
        return UUID.randomUUID().toString();
    }

    private ApiException fail(int status, String message) {
        return new ApiException(status, message);
    }

    private String text(Map<String, Object> m, String key) {
        return Objects.toString(m.get(key), "").trim();
    }

    private String need(Map<String, Object> m, String key) {
        String v = text(m, key);
        if (v.isBlank() || v.length() > 1000)
            throw fail(400, key + " is required and must not exceed 1000 characters");
        return v;
    }

    private BigDecimal num(Map<String, Object> m, String key, BigDecimal fallback) {
        try {
            return m.get(key) == null ? fallback : new BigDecimal(m.get(key).toString());
        } catch (Exception e) {
            throw fail(400, key + " must be a decimal number");
        }
    }

    private BigDecimal money(BigDecimal v) {
        if (v.abs().compareTo(new BigDecimal("9999999999999999")) > 0)
            throw fail(400, "Amount exceeds supported range");
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal qty(BigDecimal v) {
        if (v.scale() > 6 || v.abs().compareTo(new BigDecimal("999999999999")) > 0)
            throw fail(400, "Quantity supports at most six decimal places");
        return v.setScale(6, RoundingMode.HALF_UP);
    }

    private LocalDate date(String value, String field) {
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            throw fail(400, field + " must be an ISO date");
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw fail(400, "Invalid JSON record");
        }
    }

    private Map<String, Object> decode(String value) {
        try {
            return json.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Invalid stored commercial record", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> objects(Map<String, Object> m, String key) {
        Object value = m.get(key);
        if (value == null)
            return new ArrayList<>();
        if (!(value instanceof List<?> list) || list.size() > 500)
            throw fail(400, key + " must be an array of at most 500 objects");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> raw))
                throw fail(400, key + " must contain objects");
            result.add(new LinkedHashMap<>((Map<String, Object>) raw));
        }
        return result;
    }

    private boolean role(Principal p, String... values) {
        return Arrays.asList(values).contains(p.role());
    }

    private void org(Principal p) {
        if (p.tenantId() == null || role(p, "PLATFORM_ADMIN"))
            throw fail(403, "Organization membership is required");
    }

    private void allow(Principal p, String... roles) {
        org(p);
        if (!role(p, roles))
            throw fail(403, "Your role cannot perform this commercial operation");
    }

    private String module(String kind) {
        if (Set.of("accounts", "contacts", "leads", "pipelineStages", "opportunities", "activities", "quotes",
                "customerContracts").contains(kind))
            return "CRM";
        if (Set.of("items", "warehouses", "suppliers", "purchaseRequests", "purchaseOrders", "goodsReceipts",
                "goodsReturns", "rfqs", "supplierQuotes", "supplierContracts", "stockReservations", "inventoryCounts",
                "stockAdjustments").contains(kind))
            return "INVENTORY";
        if (Set.of("compensations", "payrollRuns").contains(kind))
            return "PAYROLL";
        return "FINANCE";
    }

    private void lock(Principal p, String module, boolean creation) {
        org(p);
        String status = db.queryForObject("SELECT status FROM tenants WHERE id=? FOR UPDATE", String.class,
                p.tenantId());
        if (!"ACTIVE".equals(status))
            throw fail(403, "This organization is suspended");
        Integer active = db.queryForObject(
                "SELECT COUNT(*) FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.tenant_id=? AND m.user_id=? AND m.role=? AND m.status='ACTIVE' AND u.status='ACTIVE'",
                Integer.class, p.tenantId(), p.userId(), p.role());
        if (active == null || active != 1)
            throw fail(403, "Your organization authority changed; sign in again");
        entitlements.require(p, module, creation);
    }

    private Map<String, Object> row(Map<String, Object> r) {
        Map<String, Object> m = decode(r.get("payload").toString());
        m.put("id", r.get("id"));
        m.put("status", r.get("status"));
        m.put("version", r.get("version"));
        m.put("total", r.get("total"));
        m.put("currency", r.get("currency"));
        m.put("createdBy", r.get("created_by"));
        m.put("createdAt", r.get("created_at"));
        return m;
    }

    private Map<String, Object> get(Principal p, String kind, String recordId) {
        var rows = db.queryForList("SELECT * FROM business_entities WHERE tenant_id=? AND kind=? AND id=?",
                p.tenantId(), kind, recordId);
        if (rows.isEmpty())
            throw fail(404, "Commercial record not found in your organization");
        return row(rows.getFirst());
    }

    private List<Map<String, Object>> all(Principal p, String kind) {
        return db.queryForList(
                "SELECT * FROM business_entities WHERE tenant_id=? AND kind=? ORDER BY created_at DESC,id",
                p.tenantId(), kind).stream().map(this::row).toList();
    }

    private Map<String, Object> workforce(Principal p, String kind, String recordId) {
        var rows = db.queryForList("SELECT payload,id FROM business_records WHERE tenant_id=? AND kind=? AND id=?",
                p.tenantId(), kind, recordId);
        if (rows.isEmpty())
            throw fail(400, "Referenced " + kind + " is not in your organization");
        Map<String, Object> m = decode(rows.getFirst().get("payload").toString());
        m.put("id", recordId);
        return m;
    }

    private void activeUser(Principal p, String userId) {
        Integer n = db.queryForObject(
                "SELECT COUNT(*) FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.tenant_id=? AND m.user_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'",
                Integer.class, p.tenantId(), userId);
        if (n == null || n != 1)
            throw fail(400, "Owner must be an active organization member");
    }

    private void ref(Principal p, Map<String, Object> m, String key, String kind) {
        if (!text(m, key).isEmpty())
            get(p, kind, text(m, key));
    }

    private boolean sharedReference(Principal p, String kind) {
        return kind.equals("accounts") && role(p, "FINANCE") || kind.equals("items") && role(p, "SALES", "FINANCE")
                || Set.of("budgets", "costCenters").contains(kind) && role(p, "PROCUREMENT")
                || Set.of("suppliers", "purchaseOrders").contains(kind) && role(p, "FINANCE");
    }

    private boolean crmVisible(Principal p, String kind, Map<String, Object> r) {
        if (!role(p, "SALES") || !Set
                .of("accounts", "contacts", "leads", "opportunities", "activities", "quotes", "customerContracts")
                .contains(kind))
            return true;
        String owner = text(r, "ownerId").isEmpty() ? text(r, "createdBy") : text(r, "ownerId");
        return owner.equals(p.userId()) || r.get("teamIds") instanceof List<?> team && team.contains(p.userId());
    }

    private void crmScope(Principal p, String kind, Map<String, Object> r) {
        if (!crmVisible(p, kind, r))
            throw fail(403, "CRM record belongs to another owner/team");
    }

    private void canRead(Principal p, String kind, Map<String, Object> r) {
        crmScope(p, kind, r);
        if (sharedReference(p, kind))
            return;
        if ((kind.equals("accounts") && role(p, "FINANCE")) || (kind.equals("items") && role(p, "SALES", "FINANCE")))
            return;
        if (kind.equals("expenses") && p.userId().equals(text(r, "createdBy")))
            return;
        if (!domainRead(p, module(kind)))
            throw fail(403, "Your role cannot read this business domain");
    }

    private void canWrite(Principal p, String kind) {
        if (kind.equals("expenses"))
            allow(p, "ADMIN", "HR", "MANAGER", "MEMBER", "FINANCE", "SALES", "PROCUREMENT", "PAYROLL");
        else
            domainWrite(p, module(kind));
    }

    private void domainWrite(Principal p, String module) {
        switch (module) {
        case "CRM" -> allow(p, "ADMIN", "MANAGER", "SALES");
        case "INVENTORY" -> allow(p, "ADMIN", "MANAGER", "PROCUREMENT");
        case "FINANCE" -> allow(p, "ADMIN", "MANAGER", "FINANCE");
        case "PAYROLL" -> allow(p, "ADMIN", "HR", "PAYROLL");
        default -> throw fail(403, "Unsupported domain");
        }
    }

    private boolean domainRead(Principal p, String module) {
        if (module.equals("PAYROLL"))
            return role(p, "ADMIN", "HR", "PAYROLL", "FINANCE", "AUDITOR");
        return role(p, "ADMIN", "MANAGER", "AUDITOR") || switch (module) {
        case "CRM" -> role(p, "SALES");
        case "INVENTORY" -> role(p, "PROCUREMENT");
        case "FINANCE" -> role(p, "FINANCE");
        case "PAYROLL" -> role(p, "HR", "PAYROLL");
        default -> false;
        };
    }

    private void status(Map<String, Object> r, String... allowed) {
        if (!Arrays.asList(allowed).contains(text(r, "status")))
            throw fail(409, "Action is not allowed while record is " + text(r, "status"));
    }

    private void notMaker(Principal p, Map<String, Object> r) {
        if (p.userId().equals(text(r, "createdBy")))
            throw fail(403, "A different authorized reviewer must approve this record");
    }

    private String currency(Map<String, Object> m) {
        String code = text(m, "currency");
        if (code.isEmpty())
            code = "INR";
        if (!code.matches("[A-Z]{3}"))
            throw fail(400, "Currency must be an uppercase ISO code");
        try {
            Currency.getInstance(code);
        } catch (Exception e) {
            throw fail(400, "Unknown currency");
        }
        return code;
    }

    private Map<String, Object> insert(Principal p, String kind, Map<String, Object> m, String status) {
        String recordId = id();
        Map<String, Object> payload = new LinkedHashMap<>(m);
        payload.remove("id");
        payload.remove("version");
        payload.remove("createdBy");
        BigDecimal total = money(num(payload, "total", BigDecimal.ZERO));
        String c = currency(payload);
        db.update(
                "INSERT INTO business_entities(id,tenant_id,kind,payload,status,total,currency,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?)",
                recordId, p.tenantId(), kind, encode(payload), status, total, c, p.userId(), p.userId());
        audit.log(p, "CREATE", kind, recordId);
        return get(p, kind, recordId);
    }

    private Map<String, Object> updateRow(Principal p, String kind, Map<String, Object> m, String status) {
        String recordId = text(m, "id");
        Map<String, Object> payload = new LinkedHashMap<>(m);
        for (String f : List.of("id", "version", "createdBy", "createdAt", "status"))
            payload.remove(f);
        db.update(
                "UPDATE business_entities SET payload=?,status=?,total=?,currency=?,version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND kind=? AND id=?",
                encode(payload), status, money(num(m, "total", BigDecimal.ZERO)), currency(m), p.userId(), p.tenantId(),
                kind, recordId);
        audit.log(p, "UPDATE_" + status, kind, recordId);
        return get(p, kind, recordId);
    }

    @GetMapping("/records/{kind}")
    public List<Map<String, Object>> records(@RequestAttribute Principal principal, @PathVariable String kind,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        org(principal);
        if (!KINDS.contains(kind) && !Set.of("goodsReceipts", "goodsReturns", "salesOrders").contains(kind))
            throw fail(404, "Unknown commercial record kind");
        if (page < 0 || size < 1 || size > 500)
            throw fail(400, "Invalid page or size");
        if (!kind.equals("expenses") && !domainRead(principal, module(kind)) && !sharedReference(principal, kind))
            throw fail(403, "Your role cannot read this business domain");
        List<Map<String, Object>> visible = new ArrayList<>();
        for (Map<String, Object> r : all(principal, kind)) {
            if (!crmVisible(principal, kind, r))
                continue;
            if (kind.equals("expenses") && !domainRead(principal, "FINANCE")
                    && !principal.userId().equals(text(r, "createdBy")))
                continue;
            canRead(principal, kind, r);
            if (kind.equals("items") && role(principal, "SALES", "FINANCE"))
                r.keySet().retainAll(
                        Set.of("id", "name", "sku", "unit", "category", "currency", "taxRate", "status", "version"));
            if (Set.of("budgets", "costCenters").contains(kind) && role(principal, "PROCUREMENT"))
                r.keySet().retainAll(Set.of("id", "name", "amount", "currency", "status", "version", "startDate",
                        "endDate", "costCenterId"));
            if (q.isBlank() || encode(r).toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)))
                visible.add(r);
        }
        return visible.stream().skip((long) page * size).limit(size).toList();
    }

    @PostMapping("/records/{kind}")
    @Transactional
    public Map<String, Object> create(@RequestAttribute Principal principal, @PathVariable String kind,
            @RequestBody Map<String, Object> body) {
        return save(principal, kind, null, body);
    }

    @PutMapping("/records/{kind}/{id}")
    @Transactional
    public Map<String, Object> update(@RequestAttribute Principal principal, @PathVariable String kind,
            @PathVariable String id, @RequestBody Map<String, Object> body) {
        return save(principal, kind, id, body);
    }

    private Map<String, Object> save(Principal p, String kind, String recordId, Map<String, Object> body) {
        if (!KINDS.contains(kind))
            throw fail(404, "Unknown commercial kind");
        canWrite(p, kind);
        lock(p, module(kind), recordId == null);
        Map<String, Object> old = recordId == null ? null : get(p, kind, recordId);
        if (old != null) {
            canRead(p, kind, old);
            if (num(body, "version", BigDecimal.valueOf(-1)).longValue() != ((Number) old.get("version")).longValue())
                throw fail(409, "Record changed; reload before saving");
            if (DOCUMENTS.contains(kind) || Set
                    .of("payrollRuns", "stockReservations", "inventoryCounts", "stockAdjustments", "financialPeriods")
                    .contains(kind))
                status(old, "DRAFT");
            if (kind.equals("leads") && "CONVERTED".equals(text(old, "status")))
                throw fail(409, "Converted leads are immutable");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (old != null)
            m.putAll(old);
        m.putAll(body);
        m.remove("tenantId");
        m.remove("tenant_id");
        m.remove("createdBy");
        m.remove("createdAt");
        m.remove("id");
        if (old != null) {
            m.put("createdBy", old.get("createdBy"));
            m.put("id", old.get("id"));
        }
        validate(p, kind, m, old);
        validateExtensions(p, kind, m, old);
        if (module(kind).equals("CRM") && text(m, "ownerId").isEmpty())
            m.put("ownerId", p.userId());
        if (encode(m).length() > 500000)
            throw fail(400, "Commercial record is too large");
        String initial = Set.of("leads").contains(kind) ? "NEW"
                : Set.of("accounts", "contacts", "items", "warehouses", "suppliers", "costCenters", "budgets",
                        "compensations", "pipelineStages", "opportunities", "activities", "supplierContracts",
                        "customerContracts").contains(kind) ? "ACTIVE" : "DRAFT";
        return old == null ? insert(p, kind, m, initial) : updateRow(p, kind, m, text(old, "status"));
    }

    private void validate(Principal p, String kind, Map<String, Object> m, Map<String, Object> old) {
        need(m, "name");
        currency(m);
        if (!text(m, "branchId").isEmpty())
            workforce(p, "branches", text(m, "branchId"));
        if (!text(m, "ownerId").isEmpty())
            activeUser(p, text(m, "ownerId"));
        ref(p, m, "accountId", "accounts");
        ref(p, m, "contactId", "contacts");
        ref(p, m, "stageId", "pipelineStages");
        ref(p, m, "supplierId", "suppliers");
        ref(p, m, "costCenterId", "costCenters");
        ref(p, m, "budgetId", "budgets");
        ref(p, m, "purchaseRequestId", "purchaseRequests");
        if (kind.equals("items")) {
            need(m, "sku");
            for (Map<String, Object> r : all(p, kind))
                if (!text(r, "id").equals(text(m, "id")) && text(r, "sku").equalsIgnoreCase(text(m, "sku")))
                    throw fail(409, "SKU already exists in this organization");
            if (num(m, "reorderPoint", BigDecimal.ZERO).signum() < 0)
                throw fail(400, "Reorder point cannot be negative");
        }
        if (kind.equals("leads") || kind.equals("contacts")) {
            if (text(m, "ownerId").isEmpty())
                m.put("ownerId", p.userId());
            if (!text(m, "email").isEmpty()) {
                if (!text(m, "email").matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
                    throw fail(400, "Invalid contact email");
                for (Map<String, Object> r : all(p, kind))
                    if (!text(r, "id").equals(text(m, "id")) && text(r, "email").equalsIgnoreCase(text(m, "email")))
                        throw fail(409, "A record with this email already exists");
            }
        }
        if (kind.equals("opportunities")) {
            if (text(m, "stageId").isEmpty())
                throw fail(400, "Select a configured pipeline stage");
            if (old != null && !text(old, "stageId").equals(text(m, "stageId")))
                throw fail(400, "Use the stage transition action to preserve history");
            m.put("stageHistory",
                    old == null ? List.of(
                            map("stageId", text(m, "stageId"), "actorId", p.userId(), "at", Instant.now().toString()))
                            : old.get("stageHistory"));
        }
        if (kind.equals("activities")) {
            ref(p, m, "leadId", "leads");
            ref(p, m, "opportunityId", "opportunities");
            if (!text(m, "dueDate").isEmpty())
                date(text(m, "dueDate"), "dueDate");
        }
        if (DOCUMENTS.contains(kind)) {
            if (kind.equals("invoices"))
                need(m, "accountId");
            if (kind.equals("purchaseOrders"))
                need(m, "supplierId");
            totals(p, m, kind);
            if (!text(m, "expirationDate").isEmpty())
                date(text(m, "expirationDate"), "expirationDate");
            if (!text(m, "dueDate").isEmpty())
                date(text(m, "dueDate"), "dueDate");
        }
        if (kind.equals("budgets")) {
            if (num(m, "amount", BigDecimal.ZERO).signum() <= 0)
                throw fail(400, "Budget amount must be positive");
            m.put("amount", money(num(m, "amount", BigDecimal.ZERO)));
            LocalDate a = date(need(m, "startDate"), "startDate"), b = date(need(m, "endDate"), "endDate");
            if (b.isBefore(a))
                throw fail(400, "Budget dates are reversed");
        }
        if (kind.equals("compensations")) {
            workforce(p, "employees", need(m, "employeeId"));
            LocalDate a = date(need(m, "effectiveFrom"), "effectiveFrom"),
                    b = text(m, "effectiveTo").isEmpty() ? LocalDate.MAX : date(text(m, "effectiveTo"), "effectiveTo");
            if (b.isBefore(a))
                throw fail(400, "Compensation dates are reversed");
            for (String field : List.of("baseSalary", "allowances", "deductions"))
                if (num(m, field, BigDecimal.ZERO).signum() < 0)
                    throw fail(400, "Salary components cannot be negative");
            for (Map<String, Object> r : all(p, kind))
                if (!text(r, "id").equals(text(m, "id")) && text(r, "employeeId").equals(text(m, "employeeId"))) {
                    LocalDate c = date(text(r, "effectiveFrom"), "effectiveFrom"),
                            d = text(r, "effectiveTo").isEmpty() ? LocalDate.MAX
                                    : date(text(r, "effectiveTo"), "effectiveTo");
                    if (!a.isAfter(d) && !b.isBefore(c))
                        throw fail(409, "Effective-dated compensation overlaps an existing salary record");
                }
            for (Map<String, Object> component : objects(m, "components")) {
                need(component, "name");
                if (!Set.of("EARNING", "DEDUCTION").contains(need(component, "type"))
                        || !Set.of("FIXED", "PERCENT").contains(need(component, "mode"))
                        || num(component, "amount", BigDecimal.ZERO).signum() < 0)
                    throw fail(400, "Invalid configurable salary component");
            }
        }
        if (kind.equals("payrollRuns")) {
            LocalDate a = date(need(m, "periodStart"), "periodStart"), b = date(need(m, "periodEnd"), "periodEnd");
            if (b.isBefore(a) || a.plusDays(62).isBefore(b))
                throw fail(400, "Payroll period must be ordered and no longer than 63 days");
            for (Map<String, Object> run : all(p, kind))
                if (!text(run, "id").equals(text(m, "id")) && !"VOID".equals(text(run, "status"))
                        && !"CANCELLED".equals(text(run, "status")) && text(run, "adjustmentOf").isEmpty()
                        && !a.isAfter(date(text(run, "periodEnd"), "periodEnd"))
                        && !b.isBefore(date(text(run, "periodStart"), "periodStart")))
                    throw fail(409, "Payroll period overlaps an existing run; use an explicit adjustment");
            if (old != null && !text(old, "adjustmentOf").isEmpty())
                throw fail(409, "Adjustment snapshots cannot be edited through generic records");
        }
    }

    private void totals(Principal p, Map<String, Object> m, String kind) {
        List<Map<String, Object>> lines = objects(m, "lines");
        if (lines.isEmpty())
            throw fail(400, "At least one line item is required");
        BigDecimal subtotal = BigDecimal.ZERO, tax = BigDecimal.ZERO, discount = BigDecimal.ZERO;
        for (Map<String, Object> line : lines) {
            if (!text(line, "itemId").isEmpty())
                get(p, "items", text(line, "itemId"));
            BigDecimal q = qty(num(line, "quantity", BigDecimal.ONE)),
                    price = money(num(line, "unitPrice", BigDecimal.ZERO)),
                    rate = num(line, "taxRate", BigDecimal.ZERO), off = money(num(line, "discount", BigDecimal.ZERO));
            if (q.signum() <= 0 || price.signum() < 0 || rate.signum() < 0
                    || rate.compareTo(BigDecimal.valueOf(100)) > 0 || off.signum() < 0)
                throw fail(400, "Line quantity/price/tax/discount is invalid");
            BigDecimal base = money(q.multiply(price));
            if (off.compareTo(base) > 0)
                throw fail(400, "Line discount exceeds its base amount");
            BigDecimal lineTax = money(base.subtract(off).multiply(rate).divide(BigDecimal.valueOf(100)));
            line.put("quantity", q);
            line.put("unitPrice", money(price));
            line.put("discount", off);
            line.put("taxRate", rate);
            line.put("taxAmount", lineTax);
            line.put("total", money(base.subtract(off).add(lineTax)));
            subtotal = subtotal.add(base);
            tax = tax.add(lineTax);
            discount = discount.add(off);
        }
        m.put("lines", lines);
        m.put("subtotal", money(subtotal));
        m.put("taxTotal", money(tax));
        m.put("discountTotal", money(discount));
        m.put("total", money(subtotal.subtract(discount).add(tax)));
        String taxMode = text(m, "taxMode");
        if (taxMode.isEmpty())
            taxMode = "STANDARD";
        if (!Set.of("STANDARD", "GST_INTRA", "GST_INTER").contains(taxMode))
            throw fail(400, "Unknown tax mode");
        for (String field : List.of("sellerGstin", "buyerGstin"))
            if (!text(m, field).isEmpty() && !text(m, field).matches("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]"))
                throw fail(400, "Invalid GSTIN format in " + field);
        if (!taxMode.equals("STANDARD")) {
            String state = need(m, "placeOfSupply");
            if (!state.matches("[0-9]{2}"))
                throw fail(400, "Place of supply must be a two-digit state code");
            if (!text(m, "sellerGstin").isEmpty()) {
                boolean same = text(m, "sellerGstin").substring(0, 2).equals(state);
                if (same != taxMode.equals("GST_INTRA"))
                    throw fail(400, "GST mode does not match seller state and place of supply");
            }
            if (!currency(m).equals("INR"))
                throw fail(400, "GST documents must use INR");
        }
        BigDecimal cgst = taxMode.equals("GST_INTRA") ? money(tax.divide(BigDecimal.valueOf(2))) : BigDecimal.ZERO;
        m.put("taxMode", taxMode);
        m.put("cgst", cgst);
        m.put("sgst", taxMode.equals("GST_INTRA") ? money(tax.subtract(cgst)) : BigDecimal.ZERO);
        m.put("igst", taxMode.equals("GST_INTER") ? money(tax) : BigDecimal.ZERO);
    }

    @PostMapping("/leads/{id}/convert")
    @Transactional
    public Map<String, Object> convertLead(@RequestAttribute Principal principal, @PathVariable String id) {
        domainWrite(principal, "CRM");
        lock(principal, "CRM", true);
        Map<String, Object> lead = get(principal, "leads", id);
        crmScope(principal, "leads", lead);
        status(lead, "NEW", "QUALIFIED");
        String email = text(lead, "email");
        Map<String, Object> account = all(principal, "accounts").stream()
                .filter(a -> !email.isEmpty() && email.equalsIgnoreCase(text(a, "email"))).findFirst()
                .orElseGet(() -> insert(principal, "accounts",
                        map("name", text(lead, "company").isEmpty() ? text(lead, "name") : text(lead, "company"),
                                "email", email, "ownerId", text(lead, "ownerId")),
                        "ACTIVE"));
        lead.put("accountId", account.get("id"));
        updateRow(principal, "leads", lead, "CONVERTED");
        audit.log(principal, "LEAD_CONVERTED", "leads", id);
        return account;
    }

    @PostMapping("/opportunities/{id}/stage")
    @Transactional
    public Map<String, Object> opportunityStage(@RequestAttribute Principal principal, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        domainWrite(principal, "CRM");
        lock(principal, "CRM", false);
        Map<String, Object> opportunity = get(principal, "opportunities", id);
        crmScope(principal, "opportunities", opportunity);
        String stage = need(body, "stageId");
        Map<String, Object> stageRecord = get(principal, "pipelineStages", stage);
        if ("LOST".equals(text(stageRecord, "outcome")))
            opportunity.put("lossReason", need(body, "reason"));
        opportunity.put("outcome", text(stageRecord, "outcome").isEmpty() ? "OPEN" : text(stageRecord, "outcome"));
        List<Map<String, Object>> history = objects(opportunity, "stageHistory");
        history.add(map("stageId", stage, "actorId", principal.userId(), "at", Instant.now().toString(), "reason",
                text(body, "reason")));
        opportunity.put("stageHistory", history);
        opportunity.put("stageId", stage);
        return updateRow(principal, "opportunities", opportunity, "ACTIVE");
    }

    @PostMapping("/quotes/{id}/approve")
    @Transactional
    public Map<String, Object> approveQuote(@RequestAttribute Principal principal, @PathVariable String id) {
        domainWrite(principal, "CRM");
        lock(principal, "CRM", false);
        Map<String, Object> quote = get(principal, "quotes", id);
        crmScope(principal, "quotes", quote);
        status(quote, "DRAFT");
        notMaker(principal, quote);
        if (!text(quote, "expirationDate").isEmpty()
                && date(text(quote, "expirationDate"), "expirationDate").isBefore(LocalDate.now()))
            throw fail(409, "Quote has expired");
        quote.put("approvedBy", principal.userId());
        return updateRow(principal, "quotes", quote, "APPROVED");
    }

    @PostMapping("/quotes/{id}/invoice")
    @Transactional
    public Map<String, Object> quoteInvoice(@RequestAttribute Principal principal, @PathVariable String id) {
        domainWrite(principal, "FINANCE");
        lock(principal, "FINANCE", true);
        Map<String, Object> quote = get(principal, "quotes", id);
        status(quote, "APPROVED");
        if (!text(quote, "invoiceId").isEmpty())
            return get(principal, "invoices", text(quote, "invoiceId"));
        need(quote, "accountId");
        get(principal, "accounts", text(quote, "accountId"));
        Map<String, Object> order = insert(principal, "salesOrders",
                map("name", "Order: " + text(quote, "name"), "quoteId", id, "accountId", quote.get("accountId"),
                        "lines", quote.get("lines"), "total", quote.get("total"), "currency", quote.get("currency")),
                "APPROVED");
        Map<String, Object> invoice = insert(principal, "invoices", map("name", "Invoice: " + text(quote, "name"),
                "quoteId", id, "salesOrderId", order.get("id"), "accountId", quote.get("accountId"), "lines",
                quote.get("lines"), "subtotal", quote.get("subtotal"), "taxTotal", quote.get("taxTotal"),
                "discountTotal", quote.get("discountTotal"), "total", quote.get("total"), "currency",
                quote.get("currency"), "taxMode", quote.getOrDefault("taxMode", "STANDARD"), "cgst",
                quote.getOrDefault("cgst", BigDecimal.ZERO), "sgst", quote.getOrDefault("sgst", BigDecimal.ZERO),
                "igst", quote.getOrDefault("igst", BigDecimal.ZERO), "sellerGstin",
                quote.getOrDefault("sellerGstin", ""), "buyerGstin", quote.getOrDefault("buyerGstin", ""),
                "placeOfSupply", quote.getOrDefault("placeOfSupply", "")), "DRAFT");
        quote.put("invoiceId", invoice.get("id"));
        quote.put("salesOrderId", order.get("id"));
        updateRow(principal, "quotes", quote, "APPROVED");
        return invoice;
    }

    @PostMapping("/purchaseRequests/{id}/approve")
    @Transactional
    public Map<String, Object> approveRequest(@RequestAttribute Principal principal, @PathVariable String id) {
        domainWrite(principal, "INVENTORY");
        lock(principal, "INVENTORY", false);
        Map<String, Object> r = get(principal, "purchaseRequests", id);
        status(r, "DRAFT");
        notMaker(principal, r);
        r.put("approvedBy", principal.userId());
        return updateRow(principal, "purchaseRequests", r, "APPROVED");
    }

    @PostMapping("/purchaseOrders/{id}/approve")
    @Transactional
    public Map<String, Object> approveOrder(@RequestAttribute Principal principal, @PathVariable String id) {
        domainWrite(principal, "INVENTORY");
        lock(principal, "INVENTORY", false);
        Map<String, Object> r = get(principal, "purchaseOrders", id);
        status(r, "DRAFT");
        notMaker(principal, r);
        if (!text(r, "purchaseRequestId").isEmpty())
            status(get(principal, "purchaseRequests", text(r, "purchaseRequestId")), "APPROVED");
        purchaseBudget(principal, r);
        r.put("approvedBy", principal.userId());
        r.put("received", map());
        return updateRow(principal, "purchaseOrders", r, "APPROVED");
    }

    private String commandKey(Map<String, Object> body) {
        String k = need(body, "idempotencyKey");
        if (k.length() > 120)
            throw fail(400, "idempotencyKey must be at most 120 characters");
        return k;
    }

    private String hash(Object body) {
        try {
            Object canonical = json.readValue(encode(body), Object.class);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(encode(canonical).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw fail(400, "Cannot fingerprint request");
        }
    }

    private Map<String, Object> replay(Principal p, String key, Object body) {
        var commands = db.queryForList(
                "SELECT request_hash,response_json FROM business_commands WHERE tenant_id=? AND command_key=?",
                p.tenantId(), key);
        if (commands.isEmpty())
            return null;
        if (!hash(body).equals(commands.getFirst().get("request_hash")))
            throw fail(409, "Idempotency key was already used for different request data");
        return decode(commands.getFirst().get("response_json").toString());
    }

    private Map<String, Object> complete(Principal p, String key, Object body, Map<String, Object> response) {
        db.update("INSERT INTO business_commands(tenant_id,command_key,request_hash,response_json) VALUES(?,?,?,?)",
                p.tenantId(), key, hash(body), encode(response));
        return response;
    }

    private Map<String, Object> balance(Principal p, String item, String warehouse) {
        db.update(
                "INSERT INTO stock_balances(id,tenant_id,item_id,warehouse_id) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                id(), p.tenantId(), item, warehouse);
        return db.queryForMap(
                "SELECT quantity,inventory_value FROM stock_balances WHERE tenant_id=? AND item_id=? AND warehouse_id=? FOR UPDATE",
                p.tenantId(), item, warehouse);
    }

    private BigDecimal reserved(Principal p, String item, String warehouse) {
        BigDecimal value = db.queryForObject(
                "SELECT COALESCE(SUM(CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.quantity')) AS DECIMAL(19,6))),0) FROM business_entities WHERE tenant_id=? AND kind='stockReservations' AND status='RESERVED' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.itemId'))=? AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.warehouseId'))=?",
                BigDecimal.class, p.tenantId(), item, warehouse);
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal postStock(Principal p, String item, String warehouse, BigDecimal delta, BigDecimal unitCost,
            String type, String source, String reason, String key) {
        return postStock(p, item, warehouse, delta, unitCost, type, source, reason, key, map());
    }

    private BigDecimal postStock(Principal p, String item, String warehouse, BigDecimal delta, BigDecimal unitCost,
            String type, String source, String reason, String key, Map<String, Object> metadata) {
        Map<String, Object> product = get(p, "items", item);
        get(p, "warehouses", warehouse);
        Map<String, Object> b = balance(p, item, warehouse);
        BigDecimal before = (BigDecimal) b.get("quantity"), value = (BigDecimal) b.get("inventory_value"),
                after = qty(before.add(delta));
        if (after.signum() < 0 || delta.signum() < 0 && after.compareTo(reserved(p, item, warehouse)) < 0)
            throw fail(409, "Insufficient available stock after active reservations");
        BigDecimal cost = unitCost;
        if (delta.signum() < 0) {
            BigDecimal remaining = delta.negate(), fifoValue = BigDecimal.ZERO;
            List<Map<String, Object>> layers = db.queryForList(
                    "SELECT * FROM stock_layers WHERE tenant_id=? AND item_id=? AND warehouse_id=? AND quantity_remaining>0 ORDER BY created_at,id FOR UPDATE",
                    p.tenantId(), item, warehouse);
            for (Map<String, Object> layer : layers) {
                if (!text(metadata, "batch").isEmpty() && !text(metadata, "batch").equals(layer.get("batch_code")))
                    continue;
                Object expiry = layer.get("expires_on");
                if (expiry != null && date(expiry.toString(), "expires_on").isBefore(LocalDate.now())
                        && !Set.of("ADJUSTMENT", "CYCLE_COUNT", "GOODS_RETURN").contains(type))
                    continue;
                BigDecimal take = remaining.min((BigDecimal) layer.get("quantity_remaining"));
                fifoValue = fifoValue.add(take.multiply((BigDecimal) layer.get("unit_cost")));
                db.update("UPDATE stock_layers SET quantity_remaining=quantity_remaining-? WHERE tenant_id=? AND id=?",
                        take, p.tenantId(), layer.get("id"));
                remaining = remaining.subtract(take);
                if (remaining.signum() == 0)
                    break;
            }
            if (remaining.signum() > 0)
                throw fail(409, "Selected batches have insufficient non-expired stock");
            cost = "FIFO".equals(text(product, "valuationMethod"))
                    ? fifoValue.divide(delta.negate(), 12, RoundingMode.HALF_UP)
                    : before.signum() == 0 ? BigDecimal.ZERO : value.divide(before, 12, RoundingMode.HALF_UP);
        }
        if (cost == null || cost.signum() < 0)
            throw fail(400, "Receipt unit cost cannot be negative");
        if (delta.signum() > 0) {
            String batch = text(metadata, "batch").isEmpty() ? source : text(metadata, "batch");
            if (batch == null || batch.length() > 120)
                throw fail(400, "Invalid batch code");
            Object expires = text(metadata, "expiresOn").isEmpty() ? null
                    : date(text(metadata, "expiresOn"), "expiresOn");
            db.update(
                    "INSERT INTO stock_layers(id,tenant_id,item_id,warehouse_id,batch_code,expires_on,quantity_remaining,unit_cost,source_id) VALUES(?,?,?,?,?,?,?,?,?)",
                    id(), p.tenantId(), item, warehouse, batch, expires, delta, cost.setScale(6, RoundingMode.HALF_UP),
                    source);
        }
        if (Boolean.TRUE.equals(product.get("serialTracked"))) {
            Object raw = metadata.get("serialNumbers");
            if (!(raw instanceof List<?> serials) || new HashSet<>(serials).size() != serials.size()
                    || BigDecimal.valueOf(serials.size()).compareTo(delta.abs()) != 0)
                throw fail(400, "Serial-tracked movements require one distinct serial per whole unit");
            for (Object rawSerial : serials) {
                String serial = rawSerial.toString().trim();
                if (serial.isEmpty() || serial.length() > 120)
                    throw fail(400, "Serial number must be 1 to 120 characters");
                var existing = db.queryForList(
                        "SELECT * FROM stock_serials WHERE tenant_id=? AND serial_number=? FOR UPDATE", p.tenantId(),
                        serial);
                if (delta.signum() < 0) {
                    if (existing.isEmpty() || !item.equals(existing.getFirst().get("item_id"))
                            || !warehouse.equals(existing.getFirst().get("warehouse_id"))
                            || !"AVAILABLE".equals(existing.getFirst().get("status")))
                        throw fail(409, "Serial is not available in this item/warehouse");
                    db.update(
                            "UPDATE stock_serials SET status='ISSUED',source_id=?,updated_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND serial_number=?",
                            source, p.tenantId(), serial);
                } else if (existing.isEmpty())
                    db.update(
                            "INSERT INTO stock_serials(id,tenant_id,item_id,warehouse_id,serial_number,status,source_id) VALUES(?,?,?,?,?,'AVAILABLE',?)",
                            id(), p.tenantId(), item, warehouse, serial, source);
                else {
                    if (!item.equals(existing.getFirst().get("item_id"))
                            || !"ISSUED".equals(existing.getFirst().get("status")))
                        throw fail(409, "Serial number is already present in stock");
                    db.update(
                            "UPDATE stock_serials SET status='AVAILABLE',warehouse_id=?,source_id=?,updated_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND serial_number=?",
                            warehouse, source, p.tenantId(), serial);
                }
            }
        }
        BigDecimal valueDelta = delta.multiply(cost).setScale(6, RoundingMode.HALF_UP),
                newValue = value.add(valueDelta);
        if (after.signum() == 0)
            newValue = BigDecimal.ZERO;
        if (newValue.signum() < 0)
            throw fail(409, "Stock valuation cannot become negative");
        db.update(
                "UPDATE stock_balances SET quantity=?,inventory_value=?,updated_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND item_id=? AND warehouse_id=?",
                after, newValue, p.tenantId(), item, warehouse);
        db.update(
                "INSERT INTO stock_movements(id,tenant_id,item_id,warehouse_id,quantity_delta,value_delta,movement_type,source_id,reason,command_key,actor_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                id(), p.tenantId(), item, warehouse, delta, valueDelta, type, source, reason, key, p.userId());
        if (after.compareTo(num(product, "reorderPoint", BigDecimal.ZERO)) <= 0
                && before.compareTo(num(product, "reorderPoint", BigDecimal.ZERO)) > 0)
            audit.log(p, "LOW_STOCK", "items", item);
        return cost;
    }

    @GetMapping("/inventory/stock")
    public List<Map<String, Object>> stock(@RequestAttribute Principal principal) {
        if (!domainRead(principal, "INVENTORY"))
            throw fail(403, "Inventory access is required");
        return db.queryForList(
                "SELECT b.item_id AS itemId,b.warehouse_id AS warehouseId,b.quantity,b.inventory_value AS inventoryValue,CASE WHEN b.quantity=0 THEN 0 ELSE b.inventory_value/b.quantity END AS averageCost FROM stock_balances b WHERE b.tenant_id=? ORDER BY b.item_id,b.warehouse_id",
                principal.tenantId()).stream().map(m -> {
                    Map<String, Object> item = get(principal, "items", text(m, "itemId"));
                    m.put("itemName", item.get("name"));
                    m.put("warehouseName", get(principal, "warehouses", text(m, "warehouseId")).get("name"));
                    m.put("currency", item.get("currency"));
                    BigDecimal reservation = reserved(principal, text(m, "itemId"), text(m, "warehouseId"));
                    m.put("reserved", reservation);
                    m.put("available", ((BigDecimal) m.get("quantity")).subtract(reservation));
                    m.put("reorderPoint", num(item, "reorderPoint", BigDecimal.ZERO));
                    m.put("lowStock", ((BigDecimal) m.get("quantity"))
                            .compareTo(num(item, "reorderPoint", BigDecimal.ZERO)) <= 0);
                    return m;
                }).toList();
    }

    @GetMapping("/inventory/movements")
    public List<Map<String, Object>> movements(@RequestAttribute Principal principal) {
        if (!domainRead(principal, "INVENTORY"))
            throw fail(403, "Inventory access is required");
        return db.queryForList(
                "SELECT id,item_id AS itemId,warehouse_id AS warehouseId,quantity_delta AS quantityDelta,value_delta AS valueDelta,movement_type AS type,source_id AS sourceId,reason,actor_id AS actorId,created_at AS createdAt FROM stock_movements WHERE tenant_id=? ORDER BY created_at DESC,id LIMIT 1000",
                principal.tenantId());
    }

    @PostMapping("/inventory/movements")
    @Transactional
    public Map<String, Object> movement(@RequestAttribute Principal principal, @RequestBody Map<String, Object> body) {
        domainWrite(principal, "INVENTORY");
        lock(principal, "INVENTORY", false);
        String key = "stock:" + commandKey(body);
        Map<String, Object> replay = replay(principal, key, body);
        if (replay != null)
            return replay;
        String item = need(body, "itemId"), warehouse = need(body, "warehouseId"), type = need(body, "type"),
                reason = need(body, "reason");
        BigDecimal q = qty(num(body, "quantity", BigDecimal.ZERO));
        if (q.signum() == 0)
            throw fail(400, "Quantity cannot be zero");
        if (!Set.of("RECEIPT", "ISSUE", "TRANSFER", "ADJUSTMENT", "RETURN").contains(type))
            throw fail(400, "Unknown inventory movement type");
        if (!type.equals("ADJUSTMENT") && q.signum() < 0)
            throw fail(400, "Quantity must be positive");
        BigDecimal cost = num(body, "unitCost", BigDecimal.ZERO);
        if (type.equals("ADJUSTMENT"))
            throw fail(409, "Submit a stockAdjustments record for separate reviewer approval");
        String movementId = id();
        if (type.equals("ISSUE") || type.equals("TRANSFER"))
            q = q.negate();
        BigDecimal effective = postStock(principal, item, warehouse, q, cost, type, movementId, reason, key + ":out",
                body);
        if (type.equals("TRANSFER")) {
            String to = need(body, "toWarehouseId");
            if (to.equals(warehouse))
                throw fail(400, "Choose a different destination warehouse");
            postStock(principal, item, to, q.negate(), effective, "TRANSFER_IN", movementId, reason, key + ":in", body);
        }
        audit.log(principal, "STOCK_" + type, "stock_movements", movementId);
        return complete(principal, key, body,
                map("id", movementId, "type", type, "quantity", q, "message", "Stock ledger posted"));
    }

    @PostMapping("/procurement/receipts")
    @Transactional
    public Map<String, Object> receive(@RequestAttribute Principal principal, @RequestBody Map<String, Object> body) {
        domainWrite(principal, "INVENTORY");
        lock(principal, "INVENTORY", true);
        String key = "receipt:" + commandKey(body);
        Map<String, Object> replay = replay(principal, key, body);
        if (replay != null)
            return replay;
        Map<String, Object> order = get(principal, "purchaseOrders", need(body, "orderId"));
        status(order, "APPROVED", "PARTIALLY_RECEIVED");
        String warehouse = need(body, "warehouseId");
        get(principal, "warehouses", warehouse);
        List<Map<String, Object>> receiptLines = objects(body, "lines");
        if (receiptLines.isEmpty())
            throw fail(400, "Receipt lines are required");
        Map<String, Object> received = order.get("received") instanceof Map<?, ?> raw ? decode(encode(raw))
                : new LinkedHashMap<>();
        String receiptId = id();
        Set<String> seen = new HashSet<>();
        int index = 0;
        for (Map<String, Object> line : receiptLines) {
            String item = need(line, "itemId");
            if (!seen.add(item))
                throw fail(400, "Combine duplicate item receipt lines");
            List<Map<String, Object>> matching = objects(order, "lines").stream()
                    .filter(l -> item.equals(text(l, "itemId"))).toList();
            if (matching.isEmpty())
                throw fail(400, "Received item is absent from purchase order");
            BigDecimal ordered = matching.stream().map(l -> num(l, "quantity", BigDecimal.ZERO)).reduce(BigDecimal.ZERO,
                    BigDecimal::add), prior = num(received, item, BigDecimal.ZERO),
                    quantity = qty(num(line, "quantity", BigDecimal.ZERO));
            if (quantity.signum() <= 0 || prior.add(quantity).compareTo(ordered) > 0)
                throw fail(409, "Receipt exceeds outstanding purchase-order quantity");
            if (!text(get(principal, "items", item), "currency").equals(text(order, "currency")))
                throw fail(400, "Purchase order and stocked item currencies must match; no implicit FX conversion");
            BigDecimal price = num(matching.getFirst(), "unitPrice", BigDecimal.ZERO);
            postStock(principal, item, warehouse, quantity, price, "GOODS_RECEIPT", receiptId, "Purchase order receipt",
                    key + ":" + (index++), line);
            received.put(item, prior.add(quantity));
        }
        order.put("received", received);
        boolean full = true;
        for (Map<String, Object> line : objects(order, "lines"))
            if (num(received, text(line, "itemId"), BigDecimal.ZERO)
                    .compareTo(num(line, "quantity", BigDecimal.ZERO)) < 0)
                full = false;
        updateRow(principal, "purchaseOrders", order, full ? "RECEIVED" : "PARTIALLY_RECEIVED");
        Map<String, Object> receipt = new LinkedHashMap<>(body);
        receipt.put("name", "Goods receipt " + receiptId.substring(0, 8));
        receipt.put("receiptId", receiptId);
        Map<String, Object> result = insert(principal, "goodsReceipts", receipt, "POSTED");
        audit.log(principal, "GOODS_RECEIVED", "purchaseOrders", text(order, "id"));
        return complete(principal, key, body, result);
    }

    @PostMapping("/procurement/returns")
    @Transactional
    public Map<String, Object> returnGoods(@RequestAttribute Principal principal,
            @RequestBody Map<String, Object> body) {
        domainWrite(principal, "INVENTORY");
        lock(principal, "INVENTORY", true);
        String key = "goods-return:" + commandKey(body);
        Map<String, Object> prior = replay(principal, key, body);
        if (prior != null)
            return prior;
        Map<String, Object> receipt = get(principal, "goodsReceipts", need(body, "receiptId"));
        status(receipt, "POSTED", "PARTIALLY_RETURNED");
        Map<String, Object> order = get(principal, "purchaseOrders", text(receipt, "orderId"));
        String warehouse = text(receipt, "warehouseId"), returnId = id();
        Map<String, Object> returned = receipt.get("returned") instanceof Map<?, ?> r ? decode(encode(r))
                : new LinkedHashMap<>(),
                received = order.get("received") instanceof Map<?, ?> r ? decode(encode(r)) : new LinkedHashMap<>();
        List<Map<String, Object>> lines = objects(body, "lines");
        if (lines.isEmpty())
            throw fail(400, "Return lines are required");
        Set<String> seen = new HashSet<>();
        int index = 0;
        for (Map<String, Object> line : lines) {
            String item = need(line, "itemId");
            if (!seen.add(item))
                throw fail(400, "Combine duplicate return items");
            BigDecimal originally = objects(receipt, "lines").stream().filter(l -> item.equals(text(l, "itemId")))
                    .map(l -> num(l, "quantity", BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add),
                    before = num(returned, item, BigDecimal.ZERO),
                    quantity = qty(num(line, "quantity", BigDecimal.ZERO));
            if (quantity.signum() <= 0 || before.add(quantity).compareTo(originally) > 0)
                throw fail(409, "Return exceeds the unreturned received quantity");
            postStock(principal, item, warehouse, quantity.negate(), BigDecimal.ZERO, "GOODS_RETURN", returnId,
                    need(body, "reason"), key + ":" + (index++), line);
            returned.put(item, before.add(quantity));
            received.put(item, num(received, item, BigDecimal.ZERO).subtract(quantity));
        }
        receipt.put("returned", returned);
        boolean full = objects(receipt, "lines").stream()
                .allMatch(l -> num(returned, text(l, "itemId"), BigDecimal.ZERO)
                        .compareTo(num(l, "quantity", BigDecimal.ZERO)) >= 0);
        updateRow(principal, "goodsReceipts", receipt, full ? "RETURNED" : "PARTIALLY_RETURNED");
        order.put("received", received);
        updateRow(principal, "purchaseOrders", order, "PARTIALLY_RECEIVED");
        Map<String, Object> result = insert(principal, "goodsReturns",
                map("name", "Goods return " + returnId.substring(0, 8), "receiptId", receipt.get("id"), "orderId",
                        order.get("id"), "warehouseId", warehouse, "lines", lines, "reason", text(body, "reason")),
                "POSTED");
        return complete(principal, key, body, result);
    }

    @PostMapping("/invoices/{id}/review")
    @Transactional
    public Map<String, Object> reviewInvoice(@RequestAttribute Principal principal, @PathVariable String id) {
        domainWrite(principal, "FINANCE");
        lock(principal, "FINANCE", false);
        Map<String, Object> invoice = get(principal, "invoices", id);
        status(invoice, "DRAFT");
        notMaker(principal, invoice);
        invoice.put("reviewedBy", principal.userId());
        invoice.put("reviewedAt", Instant.now().toString());
        return updateRow(principal, "invoices", invoice, "REVIEWED");
    }

    @PostMapping("/invoices/{id}/issue")
    @Transactional
    public Map<String, Object> issueInvoice(@RequestAttribute Principal principal, @PathVariable String id) {
        domainWrite(principal, "FINANCE");
        lock(principal, "FINANCE", false);
        openPeriod(principal, LocalDate.now());
        Map<String, Object> invoice = get(principal, "invoices", id);
        status(invoice, "DRAFT", "REVIEWED");
        if (Boolean.TRUE.equals(invoice.get("requireReview")) && !"REVIEWED".equals(text(invoice, "status")))
            throw fail(409, "This invoice requires independent review before issue");
        long sequence = db.queryForObject(
                "SELECT COUNT(*)+1 FROM business_entities WHERE tenant_id=? AND kind='invoices' AND status NOT IN ('DRAFT','REVIEWED')",
                Long.class, principal.tenantId());
        Map<String, Object> seller = db.queryForMap(
                "SELECT t.name,p.legal_name,p.address,p.gstin FROM tenants t LEFT JOIN organization_profiles p ON p.tenant_id=t.id WHERE t.id=?",
                principal.tenantId());
        invoice.put("sellerName", Objects.toString(seller.get("legal_name"), seller.get("name").toString()));
        invoice.put("sellerAddress", Objects.toString(seller.get("address"), ""));
        if (text(invoice, "sellerGstin").isEmpty())
            invoice.put("sellerGstin", Objects.toString(seller.get("gstin"), ""));
        Map<String, Object> buyer = get(principal, "accounts", text(invoice, "accountId"));
        invoice.put("buyerName", buyer.get("name"));
        invoice.put("buyerAddress", text(buyer, "address"));
        if (text(invoice, "buyerGstin").isEmpty())
            invoice.put("buyerGstin", text(buyer, "gstin"));
        totals(principal, invoice, "invoices");
        invoice.put("invoiceNumber", "INV-" + LocalDate.now().getYear() + "-" + String.format("%06d", sequence));
        invoice.put("issuedAt", Instant.now().toString());
        invoice.put("paid", BigDecimal.ZERO);
        invoice.put("credited", BigDecimal.ZERO);
        return updateRow(principal, "invoices", invoice, "ISSUED");
    }

    @PostMapping("/invoices/{id}/payments")
    @Transactional
    public Map<String, Object> payment(@RequestAttribute Principal principal, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return financeEntry(principal, id, body, "PAYMENT");
    }

    @PostMapping("/invoices/{id}/credit")
    @Transactional
    public Map<String, Object> credit(@RequestAttribute Principal principal, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return financeEntry(principal, id, body, "CREDIT");
    }

    @PostMapping("/invoices/{id}/refund")
    @Transactional
    public Map<String, Object> refund(@RequestAttribute Principal principal, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        domainWrite(principal, "FINANCE");
        lock(principal, "FINANCE", false);
        openPeriod(principal, LocalDate.now());
        String key = "refund:" + id + ":" + commandKey(body);
        Map<String, Object> prior = replay(principal, key, body);
        if (prior != null)
            return prior;
        Map<String, Object> invoice = get(principal, "invoices", id);
        status(invoice, "PAID", "PARTIALLY_PAID");
        BigDecimal amount = money(num(body, "amount", BigDecimal.ZERO)), paid = num(invoice, "paid", BigDecimal.ZERO);
        if (amount.signum() <= 0 || amount.compareTo(paid) > 0)
            throw fail(409, "Refund record cannot exceed allocated payments");
        String reference = need(body, "reference");
        db.update(
                "INSERT INTO finance_entries(id,tenant_id,invoice_id,entry_type,amount,reference_text,actor_id) VALUES(?,?,?,?,?,?,?)",
                id(), principal.tenantId(), id, "REFUND", amount.negate(), reference, principal.userId());
        paid = paid.subtract(amount);
        invoice.put("paid", paid);
        invoice.put("refunded", num(invoice, "refunded", BigDecimal.ZERO).add(amount));
        invoice.put("balance", num(invoice, "total", BigDecimal.ZERO).subtract(paid)
                .subtract(num(invoice, "credited", BigDecimal.ZERO)));
        return complete(principal, key, body,
                updateRow(principal, "invoices", invoice, paid.signum() == 0 ? "ISSUED" : "PARTIALLY_PAID"));
    }

    private Map<String, Object> financeEntry(Principal p, String invoiceId, Map<String, Object> body, String type) {
        domainWrite(p, "FINANCE");
        lock(p, "FINANCE", false);
        openPeriod(p, LocalDate.now());
        String key = type + ":" + invoiceId + ":" + commandKey(body);
        Map<String, Object> replay = replay(p, key, body);
        if (replay != null)
            return replay;
        Map<String, Object> invoice = get(p, "invoices", invoiceId);
        status(invoice, "ISSUED", "PARTIALLY_PAID");
        BigDecimal amount = money(num(body, "amount", BigDecimal.ZERO)), paid = num(invoice, "paid", BigDecimal.ZERO),
                credited = num(invoice, "credited", BigDecimal.ZERO), total = num(invoice, "total", BigDecimal.ZERO);
        if (amount.signum() <= 0 || paid.add(credited).add(amount).compareTo(total) > 0)
            throw fail(409, "Payment/credit must be positive and cannot exceed open invoice balance");
        String reference = type.equals("PAYMENT") ? need(body, "reference") : need(body, "reason");
        db.update(
                "INSERT INTO finance_entries(id,tenant_id,invoice_id,entry_type,amount,reference_text,actor_id) VALUES(?,?,?,?,?,?,?)",
                id(), p.tenantId(), invoiceId, type, amount, reference, p.userId());
        if (type.equals("PAYMENT"))
            paid = paid.add(amount);
        else
            credited = credited.add(amount);
        invoice.put("paid", paid);
        invoice.put("credited", credited);
        invoice.put("balance", total.subtract(paid).subtract(credited));
        String status = paid.add(credited).compareTo(total) == 0 ? (credited.compareTo(total) == 0 ? "VOID" : "PAID")
                : "PARTIALLY_PAID";
        Map<String, Object> result = updateRow(p, "invoices", invoice, status);
        audit.log(p, "INVOICE_" + type, "invoices", invoiceId);
        return complete(p, key, body, result);
    }

    @GetMapping("/invoices/{id}/entries")
    public List<Map<String, Object>> invoiceEntries(@RequestAttribute Principal principal, @PathVariable String id) {
        if (!domainRead(principal, "FINANCE"))
            throw fail(403, "Finance access is required");
        get(principal, "invoices", id);
        return db.queryForList(
                "SELECT id,entry_type AS type,amount,reference_text AS reference,created_at AS createdAt FROM finance_entries WHERE tenant_id=? AND invoice_id=? ORDER BY created_at",
                principal.tenantId(), id);
    }

    @GetMapping("/invoices/{id}/pdf")
    public ResponseEntity<byte[]> invoicePdf(@RequestAttribute Principal principal, @PathVariable String id) {
        if (!domainRead(principal, "FINANCE"))
            throw fail(403, "Finance access is required");
        Map<String, Object> invoice = get(principal, "invoices", id);
        status(invoice, "ISSUED", "PARTIALLY_PAID", "PAID", "VOID");
        List<String> lines = new ArrayList<>();
        lines.add(text(invoice, "sellerName"));
        lines.add(text(invoice, "sellerAddress"));
        lines.add("INVOICE " + text(invoice, "invoiceNumber"));
        lines.add("Seller GSTIN: " + text(invoice, "sellerGstin"));
        lines.add("Customer: " + text(invoice, "buyerName"));
        lines.add(
                "Buyer GSTIN: " + text(invoice, "buyerGstin") + "  Place of supply: " + text(invoice, "placeOfSupply"));
        lines.add("Issued: " + text(invoice, "issuedAt") + "  Due: " + text(invoice, "dueDate"));
        lines.add("");
        for (Map<String, Object> line : objects(invoice, "lines"))
            lines.add(text(line, "description") + " | qty " + text(line, "quantity") + " x " + text(line, "unitPrice")
                    + " | tax " + text(line, "taxAmount") + " | total " + text(line, "total"));
        lines.add("");
        lines.add("Subtotal: " + text(invoice, "subtotal") + "  Discount: " + text(invoice, "discountTotal"));
        lines.add("Tax: " + text(invoice, "taxTotal") + " CGST: " + text(invoice, "cgst") + " SGST: "
                + text(invoice, "sgst") + " IGST: " + text(invoice, "igst"));
        lines.add("Total " + text(invoice, "currency") + " " + text(invoice, "total") + " | Paid "
                + text(invoice, "paid") + " | Credit " + text(invoice, "credited"));
        return pdf("invoice-" + UUID.fromString(id) + ".pdf", lines);
    }

    @PostMapping("/invoices/{id}/email")
    @Transactional
    public Map<String, Object> emailInvoice(@RequestAttribute Principal principal, @PathVariable String id) {
        domainWrite(principal, "FINANCE");
        lock(principal, "FINANCE", false);
        Map<String, Object> invoice = get(principal, "invoices", id);
        status(invoice, "ISSUED", "PARTIALLY_PAID", "PAID");
        Map<String, Object> account = get(principal, "accounts", text(invoice, "accountId"));
        String email = need(account, "email");
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
            throw fail(400, "Customer email is invalid");
        mail.queue(email, "Invoice " + text(invoice, "invoiceNumber") + " from " + text(invoice, "sellerName"),
                "Invoice: " + text(invoice, "invoiceNumber") + "\nAmount: " + text(invoice, "currency") + " "
                        + text(invoice, "total") + "\nDue: " + text(invoice, "dueDate")
                        + "\nPlease contact your account representative for the authenticated invoice document.\n"
                        + baseUrl);
        audit.log(principal, "INVOICE_EMAIL_QUEUED", "invoices", id);
        return map("message", "Invoice summary queued to the customer email");
    }

    @PostMapping("/expenses/{id}/submit")
    @Transactional
    public Map<String, Object> submitExpense(@RequestAttribute Principal principal, @PathVariable String id) {
        canWrite(principal, "expenses");
        lock(principal, "FINANCE", false);
        Map<String, Object> expense = get(principal, "expenses", id);
        if (!principal.userId().equals(text(expense, "createdBy")))
            throw fail(403, "Only the claimant can submit an expense");
        status(expense, "DRAFT");
        return updateRow(principal, "expenses", expense, "SUBMITTED");
    }

    @PostMapping("/expenses/{id}/decision")
    @Transactional
    public Map<String, Object> expenseDecision(@RequestAttribute Principal principal, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        domainWrite(principal, "FINANCE");
        lock(principal, "FINANCE", false);
        Map<String, Object> expense = get(principal, "expenses", id);
        status(expense, "SUBMITTED", "MANAGER_APPROVED");
        notMaker(principal, expense);
        boolean finalStage = "MANAGER_APPROVED".equals(text(expense, "status"));
        if (finalStage) {
            allow(principal, "ADMIN", "FINANCE");
            if (principal.userId().equals(text(expense, "managerReviewedBy")))
                throw fail(403, "Finance reviewer must differ from the manager reviewer");
        } else
            allow(principal, "ADMIN", "MANAGER");
        String decision = need(body, "decision");
        if (!Set.of("APPROVED", "REJECTED").contains(decision))
            throw fail(400, "Invalid expense decision");
        if (decision.equals("APPROVED") && finalStage)
            openPeriod(principal, LocalDate.now());
        if (decision.equals("APPROVED") && finalStage && !text(expense, "budgetId").isEmpty()) {
            Map<String, Object> budget = get(principal, "budgets", text(expense, "budgetId"));
            if (!text(budget, "currency").equals(text(expense, "currency")))
                throw fail(400, "Expense and budget currencies must match");
            LocalDate today = LocalDate.now();
            if (today.isBefore(date(text(budget, "startDate"), "startDate"))
                    || today.isAfter(date(text(budget, "endDate"), "endDate")))
                throw fail(409, "Expense budget is not active for this date");
            BigDecimal spent = all(principal, "expenses").stream()
                    .filter(e -> text(e, "budgetId").equals(text(expense, "budgetId"))
                            && Set.of("APPROVED", "SETTLED").contains(text(e, "status")))
                    .map(e -> num(e, "total", BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (spent.add(num(expense, "total", BigDecimal.ZERO)).compareTo(num(budget, "amount", BigDecimal.ZERO)) > 0)
                throw fail(409, "Expense exceeds remaining budget");
        }
        if (decision.equals("APPROVED") && finalStage)
            purchaseBudget(principal, expense);
        expense.put(finalStage ? "reviewedBy" : "managerReviewedBy", principal.userId());
        expense.put("reviewReason", text(body, "reason"));
        return updateRow(principal, "expenses", expense,
                decision.equals("APPROVED") && !finalStage ? "MANAGER_APPROVED" : decision);
    }

    @PostMapping("/expenses/{id}/settle")
    @Transactional
    public Map<String, Object> settleExpense(@RequestAttribute Principal principal, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        domainWrite(principal, "FINANCE");
        lock(principal, "FINANCE", false);
        openPeriod(principal, LocalDate.now());
        Map<String, Object> expense = get(principal, "expenses", id);
        status(expense, "APPROVED");
        notMaker(principal, expense);
        expense.put("settlementReference", need(body, "reference"));
        expense.put("settledAt", Instant.now().toString());
        return updateRow(principal, "expenses", expense, "SETTLED");
    }

    private List<Map<String, Object>> employees(Principal p) {
        return db.queryForList("SELECT id,payload FROM business_records WHERE tenant_id=? AND kind='employees'",
                p.tenantId()).stream().map(r -> {
                    Map<String, Object> e = decode(r.get("payload").toString());
                    e.put("id", r.get("id"));
                    return e;
                }).toList();
    }

    @PostMapping("/payroll/runs/{id}/calculate")
    @Transactional
    public Map<String, Object> calculate(@RequestAttribute Principal principal, @PathVariable String id) {
        allow(principal, "ADMIN", "HR", "PAYROLL");
        lock(principal, "PAYROLL", false);
        Map<String, Object> run = get(principal, "payrollRuns", id);
        status(run, "DRAFT");
        if (!text(run, "adjustmentOf").isEmpty())
            throw fail(409, "An adjustment run already contains its explicit correction snapshot");
        LocalDate start = date(text(run, "periodStart"), "periodStart"),
                end = date(text(run, "periodEnd"), "periodEnd");
        db.update("DELETE FROM payroll_entries WHERE tenant_id=? AND run_id=?", principal.tenantId(), id);
        BigDecimal grossTotal = BigDecimal.ZERO, deductionTotal = BigDecimal.ZERO;
        int count = 0;
        for (Map<String, Object> employee : employees(principal)) {
            String employeeId = text(employee, "id");
            List<Map<String, Object>> salaries = all(principal, "compensations").stream()
                    .filter(c -> employeeId.equals(text(c, "employeeId"))).toList();
            if (salaries.isEmpty())
                continue;
            BigDecimal gross = BigDecimal.ZERO, deductions = BigDecimal.ZERO;
            List<Map<String, Object>> daily = new ArrayList<>();
            String bank = "";
            for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
                LocalDate current = day;
                Optional<Map<String, Object>> salary = salaries.stream()
                        .filter(c -> !current.isBefore(date(text(c, "effectiveFrom"), "effectiveFrom"))
                                && (text(c, "effectiveTo").isEmpty()
                                        || !current.isAfter(date(text(c, "effectiveTo"), "effectiveTo"))))
                        .findFirst();
                if (salary.isEmpty())
                    continue;
                if (!text(employee, "joinDate").isEmpty() && day.isBefore(date(text(employee, "joinDate"), "joinDate")))
                    continue;
                if ("OFFBOARDED".equals(text(employee, "status")) && !text(employee, "effectiveDate").isEmpty()
                        && !day.isBefore(date(text(employee, "effectiveDate"), "effectiveDate")))
                    continue;
                Map<String, Object> c = salary.get();
                if (!text(c, "currency").equals(text(run, "currency")))
                    throw fail(400, "Compensation currency differs from payroll run");
                BigDecimal base = num(c, "baseSalary", BigDecimal.ZERO),
                        earn = base.add(num(c, "allowances", BigDecimal.ZERO)),
                        deduct = num(c, "deductions", BigDecimal.ZERO);
                for (Map<String, Object> component : objects(c, "components")) {
                    BigDecimal amount = num(component, "amount", BigDecimal.ZERO);
                    if ("PERCENT".equals(text(component, "mode")))
                        amount = base.multiply(amount).divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
                    if ("EARNING".equals(text(component, "type")))
                        earn = earn.add(amount);
                    else
                        deduct = deduct.add(amount);
                }
                BigDecimal divisor = BigDecimal.valueOf(day.lengthOfMonth());
                BigDecimal dayGross = earn.divide(divisor, 10, RoundingMode.HALF_UP),
                        dayDeduct = deduct.divide(divisor, 10, RoundingMode.HALF_UP);
                String user = text(employee, "userId");
                Integer unpaid = user.isEmpty() ? 0
                        : db.queryForObject(
                                "SELECT COUNT(*) FROM leave_requests WHERE tenant_id=? AND user_id=? AND status='APPROVED' AND start_date<=? AND end_date>=? AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(policy_snapshot,'$.payrollDeduct')),'false')='true'",
                                Integer.class, principal.tenantId(), user, day, day);
                if (unpaid != null && unpaid > 0)
                    dayDeduct = dayDeduct.add(dayGross);
                Integer attendance = user.isEmpty() ? 0
                        : db.queryForObject(
                                "SELECT COUNT(*) FROM attendance WHERE tenant_id=? AND user_id=? AND work_date=? AND out_at IS NOT NULL",
                                Integer.class, principal.tenantId(), user, day);
                if (Boolean.TRUE.equals(c.get("requireAttendance")) && (attendance == null || attendance == 0)
                        && day.getDayOfWeek().getValue() <= 5 && (unpaid == null || unpaid == 0))
                    dayDeduct = dayDeduct.add(dayGross);
                gross = gross.add(dayGross);
                deductions = deductions.add(dayDeduct);
                bank = text(c, "bankAccount");
                daily.add(map("date", day.toString(), "compensationId", c.get("id"), "gross", money(dayGross),
                        "deductions", money(dayDeduct), "unpaidLeave", unpaid == null ? 0 : unpaid,
                        "attendanceSessions", attendance == null ? 0 : attendance));
            }
            if (daily.isEmpty())
                continue;
            gross = money(gross);
            deductions = money(deductions).min(gross);
            BigDecimal net = money(gross.subtract(deductions));
            Map<String, Object> snapshot = map("daily", daily, "bankAccount", bank, "employeeCode",
                    text(employee, "employeeCode"), "calculatedBy", principal.userId(), "calculatedAt",
                    Instant.now().toString(), "rule",
                    "Monthly fixed components prorated by calendar days; optional approved unpaid-leave/attendance deductions. No statutory certification.");
            db.update(
                    "INSERT INTO payroll_entries(id,tenant_id,run_id,employee_id,user_id,employee_name,gross,deductions,net,currency,snapshot) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    id(), principal.tenantId(), id, employeeId,
                    text(employee, "userId").isEmpty() ? null : text(employee, "userId"), text(employee, "name"), gross,
                    deductions, net, text(run, "currency"), encode(snapshot));
            grossTotal = grossTotal.add(gross);
            deductionTotal = deductionTotal.add(deductions);
            count++;
        }
        if (count == 0)
            throw fail(409, "No employee has eligible effective-dated compensation for this period");
        run.put("grossTotal", money(grossTotal));
        run.put("deductionTotal", money(deductionTotal));
        run.put("total", money(grossTotal.subtract(deductionTotal)));
        run.put("employeeCount", count);
        run.put("calculatedBy", principal.userId());
        return updateRow(principal, "payrollRuns", run, "CALCULATED");
    }

    @PostMapping("/payroll/runs/{id}/reopen")
    @Transactional
    public Map<String, Object> reopenPayroll(@RequestAttribute Principal principal, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        allow(principal, "ADMIN", "HR", "PAYROLL");
        lock(principal, "PAYROLL", false);
        Map<String, Object> run = get(principal, "payrollRuns", id);
        status(run, "CALCULATED", "REVIEWED");
        if (!text(run, "adjustmentOf").isEmpty())
            throw fail(409, "Create a new explicit adjustment rather than reopening a correction snapshot");
        run.put("reopenReason", need(body, "reason"));
        run.remove("reviewedBy");
        run.remove("calculatedBy");
        db.update("DELETE FROM payroll_entries WHERE tenant_id=? AND run_id=?", principal.tenantId(), id);
        return updateRow(principal, "payrollRuns", run, "DRAFT");
    }

    @PostMapping("/payroll/runs/{id}/review")
    @Transactional
    public Map<String, Object> reviewPayroll(@RequestAttribute Principal principal, @PathVariable String id) {
        allow(principal, "ADMIN", "HR", "FINANCE");
        lock(principal, "PAYROLL", false);
        Map<String, Object> run = get(principal, "payrollRuns", id);
        status(run, "CALCULATED");
        notMaker(principal, run);
        if (principal.userId().equals(text(run, "calculatedBy")))
            throw fail(403, "Payroll calculator cannot review their own calculation");
        run.put("reviewedBy", principal.userId());
        return updateRow(principal, "payrollRuns", run, "REVIEWED");
    }

    @PostMapping("/payroll/runs/{id}/finalize")
    @Transactional
    public Map<String, Object> finalizePayroll(@RequestAttribute Principal principal, @PathVariable String id) {
        allow(principal, "ADMIN", "HR", "FINANCE");
        lock(principal, "PAYROLL", false);
        Map<String, Object> run = get(principal, "payrollRuns", id);
        status(run, "REVIEWED");
        for (LocalDate d = date(text(run, "periodStart"), "periodStart"); !d
                .isAfter(date(text(run, "periodEnd"), "periodEnd")); d = d.plusDays(1))
            openPeriod(principal, d);
        notMaker(principal, run);
        if (!principal.userId().equals(text(run, "reviewedBy")))
            throw fail(403, "Only the recorded checker may finalize the reviewed run");
        run.put("finalizedAt", Instant.now().toString());
        run.put("finalizedBy", principal.userId());
        return updateRow(principal, "payrollRuns", run, "FINALIZED");
    }

    @GetMapping("/payroll/runs/{id}/entries")
    public List<Map<String, Object>> payrollEntries(@RequestAttribute Principal principal, @PathVariable String id) {
        allow(principal, "ADMIN", "HR", "PAYROLL", "FINANCE", "AUDITOR");
        get(principal, "payrollRuns", id);
        return entryRows(principal, id, false);
    }

    private List<Map<String, Object>> entryRows(Principal p, String runId, boolean personal) {
        return db.queryForList(
                "SELECT id,run_id AS runId,employee_id AS employeeId,user_id AS userId,employee_name AS employeeName,gross,deductions,net,currency,snapshot FROM payroll_entries WHERE tenant_id=? AND run_id=? ORDER BY employee_name",
                p.tenantId(), runId).stream().filter(r -> !personal || p.userId().equals(text(r, "userId"))).map(r -> {
                    Map<String, Object> snapshot = decode(r.get("snapshot").toString());
                    if (personal)
                        snapshot.remove("bankAccount");
                    r.put("snapshot", snapshot);
                    return r;
                }).toList();
    }

    @GetMapping("/payroll/payslips")
    public List<Map<String, Object>> payslips(@RequestAttribute Principal principal) {
        org(principal);
        List<Map<String, Object>> slips = new ArrayList<>();
        for (Map<String, Object> run : all(principal, "payrollRuns"))
            if ("FINALIZED".equals(text(run, "status")))
                for (Map<String, Object> entry : entryRows(principal, text(run, "id"), true)) {
                    entry.put("periodStart", run.get("periodStart"));
                    entry.put("periodEnd", run.get("periodEnd"));
                    entry.put("runName", run.get("name"));
                    slips.add(entry);
                }
        return slips;
    }

    @GetMapping("/payroll/payslips/{id}/pdf")
    public ResponseEntity<byte[]> payslipPdf(@RequestAttribute Principal principal, @PathVariable String id) {
        org(principal);
        var rows = db.queryForList("SELECT run_id,user_id FROM payroll_entries WHERE tenant_id=? AND id=?",
                principal.tenantId(), id);
        if (rows.isEmpty())
            throw fail(404, "Payslip not found");
        String runId = rows.getFirst().get("run_id").toString();
        if (!role(principal, "ADMIN", "HR", "FINANCE", "PAYROLL", "AUDITOR")
                && !principal.userId().equals(rows.getFirst().get("user_id")))
            throw fail(403, "This is another employee's payslip");
        Map<String, Object> run = get(principal, "payrollRuns", runId);
        status(run, "FINALIZED");
        Map<String, Object> entry = entryRows(principal, runId, false).stream().filter(r -> id.equals(text(r, "id")))
                .findFirst().orElseThrow();
        return pdf("payslip-" + UUID.fromString(id) + ".pdf",
                List.of("PAYSLIP", text(entry, "employeeName"),
                        "Period: " + text(run, "periodStart") + " to " + text(run, "periodEnd"),
                        "Gross: " + text(entry, "currency") + " " + text(entry, "gross"),
                        "Deductions: " + text(entry, "deductions"), "Net pay: " + text(entry, "net"),
                        "Finalized: " + text(run, "finalizedAt"),
                        "This statement is generated from the locked payroll snapshot."));
    }

    private ResponseEntity<byte[]> pdf(String filename, List<String> lines) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "attachment; filename=" + filename).header("Cache-Control", "no-store")
                .body(BusinessPdf.document(lines));
    }

    @GetMapping("/payroll/runs/{id}/bank-export")
    public ResponseEntity<String> bankExport(@RequestAttribute Principal principal, @PathVariable String id) {
        allow(principal, "ADMIN", "HR", "PAYROLL", "FINANCE");
        Map<String, Object> run = get(principal, "payrollRuns", id);
        status(run, "FINALIZED");
        StringBuilder csv = new StringBuilder("employee_code,employee_name,bank_account,currency,net_amount\r\n");
        for (Map<String, Object> entry : entryRows(principal, id, false)) {
            Map<String, Object> snapshot = decode(encode(entry.get("snapshot")));
            String bank = text(snapshot, "bankAccount");
            if (bank.isEmpty())
                throw fail(409, "Every payroll entry needs a bank account before export");
            csv.append(csv(text(snapshot, "employeeCode"))).append(',').append(csv(text(entry, "employeeName")))
                    .append(',').append(csv(bank)).append(',').append(csv(text(entry, "currency"))).append(',')
                    .append(entry.get("net")).append("\r\n");
        }
        audit.log(principal, "BANK_FILE_EXPORTED", "payrollRuns", id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=payroll-" + UUID.fromString(id) + ".csv")
                .body(csv.toString());
    }

    private String csv(String value) {
        String safe = value;
        if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0)
            safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    @PostMapping("/payroll/runs/{id}/adjustments")
    @Transactional
    public Map<String, Object> adjustPayroll(@RequestAttribute Principal principal, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        allow(principal, "ADMIN", "HR", "PAYROLL");
        lock(principal, "PAYROLL", true);
        Map<String, Object> original = get(principal, "payrollRuns", id);
        status(original, "FINALIZED");
        String employeeId = need(body, "employeeId"), reason = need(body, "reason");
        Map<String, Object> old = entryRows(principal, id, false).stream()
                .filter(e -> employeeId.equals(text(e, "employeeId"))).findFirst()
                .orElseThrow(() -> fail(404, "Employee was not paid in the original run"));
        BigDecimal amount = money(num(body, "amount", BigDecimal.ZERO));
        if (amount.signum() == 0)
            throw fail(400, "Adjustment must be nonzero");
        Map<String, Object> run = insert(principal, "payrollRuns",
                map("name", "Adjustment: " + text(original, "name"), "periodStart", original.get("periodStart"),
                        "periodEnd", original.get("periodEnd"), "currency", original.get("currency"), "adjustmentOf",
                        id, "total", amount, "reason", reason, "calculatedBy", principal.userId()),
                "CALCULATED");
        Map<String, Object> snapshot = decode(encode(old.get("snapshot")));
        snapshot.put("adjustmentReason", reason);
        snapshot.put("originalRunId", id);
        db.update(
                "INSERT INTO payroll_entries(id,tenant_id,run_id,employee_id,user_id,employee_name,gross,deductions,net,currency,snapshot) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                id(), principal.tenantId(), run.get("id"), employeeId, old.get("userId"), old.get("employeeName"),
                amount, BigDecimal.ZERO, amount, old.get("currency"), encode(snapshot));
        return run;
    }

    @PostMapping("/leads/{id}/status")
    @Transactional
    public Map<String, Object> leadStatus(@RequestAttribute Principal principal, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        domainWrite(principal, "CRM");
        lock(principal, "CRM", false);
        Map<String, Object> lead = get(principal, "leads", id);
        crmScope(principal, "leads", lead);
        status(lead, "NEW", "QUALIFIED", "LOST", "DISQUALIFIED");
        String next = need(body, "status");
        if (!Set.of("NEW", "QUALIFIED", "LOST", "DISQUALIFIED").contains(next))
            throw fail(400, "Invalid lead status");
        String reason = Set.of("LOST", "DISQUALIFIED").contains(next) ? need(body, "reason") : text(body, "reason");
        List<Map<String, Object>> history = objects(lead, "statusHistory");
        history.add(
                map("status", next, "reason", reason, "actorId", principal.userId(), "at", Instant.now().toString()));
        lead.put("statusHistory", history);
        return updateRow(principal, "leads", lead, next);
    }

    @PostMapping("/quotes/{id}/revise")
    @Transactional
    public Map<String, Object> reviseQuote(@RequestAttribute Principal principal, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        domainWrite(principal, "CRM");
        lock(principal, "CRM", true);
        Map<String, Object> quote = get(principal, "quotes", id);
        crmScope(principal, "quotes", quote);
        status(quote, "DRAFT", "APPROVED");
        Map<String, Object> revision = new LinkedHashMap<>(quote);
        for (String key : List.of("id", "version", "createdAt", "createdBy", "invoiceId", "salesOrderId", "approvedBy",
                "status"))
            revision.remove(key);
        revision.put("revisionOf", id);
        revision.put("revisionNumber", num(quote, "revisionNumber", BigDecimal.ONE).intValue() + 1);
        revision.put("revisionReason", need(body, "reason"));
        return insert(principal, "quotes", revision, "DRAFT");
    }

    @GetMapping("/crm/summary")
    public Map<String, Object> crmSummary(@RequestAttribute Principal principal) {
        if (!domainRead(principal, "CRM"))
            throw fail(403, "CRM access is required");
        Map<String, Map<String, Object>> forecasts = new TreeMap<>();
        Map<String, Integer> stages = new TreeMap<>(), losses = new TreeMap<>();
        for (Map<String, Object> opportunity : all(principal, "opportunities")) {
            if (!crmVisible(principal, "opportunities", opportunity))
                continue;
            Map<String, Object> stage = get(principal, "pipelineStages", text(opportunity, "stageId"));
            stages.merge(text(stage, "name"), 1, Integer::sum);
            if ("LOST".equals(text(stage, "outcome"))) {
                losses.merge(
                        text(opportunity, "lossReason").isEmpty() ? "Unspecified" : text(opportunity, "lossReason"), 1,
                        Integer::sum);
                continue;
            }
            String month = text(opportunity, "expectedCloseDate").isEmpty() ? "Unscheduled"
                    : date(text(opportunity, "expectedCloseDate"), "expectedCloseDate").toString().substring(0, 7),
                    currency = text(opportunity, "currency"), key = currency + ":" + month;
            Map<String, Object> forecast = forecasts.computeIfAbsent(key, k -> map("currency", currency, "month", month,
                    "amount", BigDecimal.ZERO, "weightedAmount", BigDecimal.ZERO, "count", 0));
            BigDecimal amount = money(num(opportunity, "amount", BigDecimal.ZERO)), weighted = money(
                    amount.multiply(num(stage, "probability", BigDecimal.ZERO)).divide(BigDecimal.valueOf(100)));
            forecast.put("amount", ((BigDecimal) forecast.get("amount")).add(amount));
            forecast.put("weightedAmount", ((BigDecimal) forecast.get("weightedAmount")).add(weighted));
            forecast.put("count", ((Integer) forecast.get("count")) + 1);
        }
        return map("stages", stages, "lostReasons", losses, "forecasts", forecasts.values());
    }

    @GetMapping("/crm/accounts/{id}/timeline")
    public List<Map<String, Object>> customerTimeline(@RequestAttribute Principal principal, @PathVariable String id) {
        if (!domainRead(principal, "CRM"))
            throw fail(403, "CRM access is required");
        Map<String, Object> account = get(principal, "accounts", id);
        crmScope(principal, "accounts", account);
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (String kind : List.of("activities", "opportunities", "quotes", "customerContracts", "contacts"))
            for (Map<String, Object> record : all(principal, kind))
                if (id.equals(text(record, "accountId")) && crmVisible(principal, kind, record)) {
                    record.put("kind", kind);
                    timeline.add(record);
                }
        timeline.sort(Comparator.comparing(r -> text(r, "createdAt")));
        return timeline;
    }

    @GetMapping("/crm/contracts/renewals")
    public List<Map<String, Object>> renewals(@RequestAttribute Principal principal,
            @RequestParam(defaultValue = "30") int days) {
        if (!domainRead(principal, "CRM") || days < 0 || days > 366)
            throw fail(403, "CRM access and valid renewal window are required");
        LocalDate cutoff = LocalDate.now().plusDays(days);
        return all(principal, "customerContracts")
                .stream().filter(r -> crmVisible(principal, "customerContracts", r)
                        && "ACTIVE".equals(text(r, "status")) && !date(text(r, "endDate"), "endDate").isAfter(cutoff))
                .toList();
    }

    @PostMapping("/customerContracts/{id}/renew")
    @Transactional
    public Map<String, Object> renewContract(@RequestAttribute Principal principal, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        domainWrite(principal, "CRM");
        lock(principal, "CRM", true);
        Map<String, Object> contract = get(principal, "customerContracts", id);
        crmScope(principal, "customerContracts", contract);
        status(contract, "ACTIVE");
        LocalDate start = date(need(body, "startDate"), "startDate"), end = date(need(body, "endDate"), "endDate");
        if (!start.isAfter(date(text(contract, "endDate"), "endDate")) || end.isBefore(start))
            throw fail(400, "Renewal must follow the previous contract period");
        Map<String, Object> next = new LinkedHashMap<>(contract);
        next.put("startDate", start.toString());
        next.put("endDate", end.toString());
        next.put("renewalOf", id);
        for (String field : List.of("id", "version", "status", "createdBy", "createdAt"))
            next.remove(field);
        Map<String, Object> renewed = insert(principal, "customerContracts", next, "ACTIVE");
        contract.put("renewedContractId", renewed.get("id"));
        updateRow(principal, "customerContracts", contract, "RENEWED");
        return renewed;
    }

    @GetMapping("/procurement/supplier-performance")
    public List<Map<String, Object>> supplierPerformance(@RequestAttribute Principal principal) {
        if (!domainRead(principal, "INVENTORY"))
            throw fail(403, "Procurement access is required");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> supplier : all(principal, "suppliers")) {
            String supplierId = text(supplier, "id");
            List<Map<String, Object>> orders = all(principal, "purchaseOrders").stream()
                    .filter(r -> supplierId.equals(text(r, "supplierId"))).toList();
            Set<String> orderIds = new HashSet<>();
            orders.forEach(o -> orderIds.add(text(o, "id")));
            BigDecimal received = BigDecimal.ZERO, returned = BigDecimal.ZERO;
            int onTime = 0, late = 0;
            for (Map<String, Object> receipt : all(principal, "goodsReceipts"))
                if (orderIds.contains(text(receipt, "orderId"))) {
                    received = received.add(objects(receipt, "lines").stream()
                            .map(l -> num(l, "quantity", BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add));
                    Map<String, Object> order = orders.stream()
                            .filter(o -> text(o, "id").equals(text(receipt, "orderId"))).findFirst().orElseThrow();
                    if (!text(order, "dueDate").isEmpty()) {
                        LocalDate delivered = date(text(receipt, "createdAt").substring(0, 10), "createdAt");
                        if (delivered.isAfter(date(text(order, "dueDate"), "dueDate")))
                            late++;
                        else
                            onTime++;
                    }
                }
            for (Map<String, Object> ret : all(principal, "goodsReturns"))
                if (orderIds.contains(text(ret, "orderId")))
                    returned = returned.add(objects(ret, "lines").stream().map(l -> num(l, "quantity", BigDecimal.ZERO))
                            .reduce(BigDecimal.ZERO, BigDecimal::add));
            result.add(map("supplierId", supplierId, "supplierName", supplier.get("name"), "orders", orders.size(),
                    "receivedQuantity", received, "returnedQuantity", returned, "onTimeReceipts", onTime,
                    "lateReceipts", late, "returnRate", received.signum() == 0 ? BigDecimal.ZERO
                            : returned.multiply(BigDecimal.valueOf(100)).divide(received, 2, RoundingMode.HALF_UP)));
        }
        return result;
    }

    @GetMapping("/finance/budgets/summary")
    public List<Map<String, Object>> budgetSummary(@RequestAttribute Principal principal) {
        if (!domainRead(principal, "FINANCE"))
            throw fail(403, "Finance access is required");
        return all(principal, "budgets").stream().map(b -> {
            BigDecimal spent = BigDecimal.ZERO;
            for (String kind : List.of("expenses", "purchaseOrders"))
                for (Map<String, Object> record : all(principal, kind))
                    if (text(b, "id").equals(text(record, "budgetId"))
                            && Set.of("APPROVED", "SETTLED", "PARTIALLY_RECEIVED", "RECEIVED")
                                    .contains(text(record, "status")))
                        spent = spent.add(num(record, "total", BigDecimal.ZERO));
            b.put("committed", spent);
            b.put("remaining", num(b, "amount", BigDecimal.ZERO).subtract(spent));
            return b;
        }).toList();
    }

    @PostMapping("/invoices/reminders")
    @Transactional
    public Map<String, Object> invoiceReminders(@RequestAttribute Principal principal) {
        domainWrite(principal, "FINANCE");
        lock(principal, "FINANCE", false);
        int queued = 0;
        for (Map<String, Object> invoice : all(principal, "invoices")) {
            if (!Set.of("ISSUED", "PARTIALLY_PAID").contains(text(invoice, "status"))
                    || text(invoice, "dueDate").isEmpty()
                    || !date(text(invoice, "dueDate"), "dueDate").isBefore(LocalDate.now()))
                continue;
            String key = "invoice-reminder:" + text(invoice, "id") + ":" + LocalDate.now();
            Integer prior = db.queryForObject(
                    "SELECT COUNT(*) FROM business_commands WHERE tenant_id=? AND command_key=?", Integer.class,
                    principal.tenantId(), key);
            if (prior != null && prior > 0)
                continue;
            Map<String, Object> account = get(principal, "accounts", text(invoice, "accountId"));
            String email = text(account, "email");
            if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
                continue;
            BigDecimal balance = num(invoice, "total", BigDecimal.ZERO).subtract(num(invoice, "paid", BigDecimal.ZERO))
                    .subtract(num(invoice, "credited", BigDecimal.ZERO));
            if (balance.signum() <= 0)
                continue;
            mail.queue(email, "Overdue invoice " + text(invoice, "invoiceNumber"),
                    "Invoice " + text(invoice, "invoiceNumber") + " has outstanding " + text(invoice, "currency") + " "
                            + balance + " due " + text(invoice, "dueDate")
                            + ". Contact your account representative for reconciliation.");
            complete(principal, key, map("id", invoice.get("id"), "date", LocalDate.now().toString()), map("invoiceId",
                    invoice.get("id"), "recipient", email, "queuedAt", Instant.now().toString(), "balance", balance));
            audit.log(principal, "INVOICE_REMINDER_QUEUED", "invoices", text(invoice, "id"));
            queued++;
        }
        return map("queued", queued);
    }

    @GetMapping("/invoices/{id}/reminders")
    public List<Map<String, Object>> reminderHistory(@RequestAttribute Principal principal, @PathVariable String id) {
        if (!domainRead(principal, "FINANCE"))
            throw fail(403, "Finance access is required");
        get(principal, "invoices", id);
        return db.queryForList(
                "SELECT response_json,created_at FROM business_commands WHERE tenant_id=? AND command_key LIKE ? ORDER BY created_at DESC",
                principal.tenantId(), "invoice-reminder:" + id + ":%").stream()
                .map(r -> decode(r.get("response_json").toString())).toList();
    }

    private void validateExtensions(Principal p, String kind, Map<String, Object> m, Map<String, Object> old) {
        if (kind.equals("pipelineStages")) {
            BigDecimal probability = num(m, "probability", BigDecimal.ZERO);
            if (probability.signum() < 0 || probability.compareTo(BigDecimal.valueOf(100)) > 0)
                throw fail(400, "Stage probability must be from0 to100");
            m.put("probability", probability);
            String outcome = text(m, "outcome");
            if (outcome.isEmpty())
                m.put("outcome", "OPEN");
            else if (!Set.of("OPEN", "WON", "LOST").contains(outcome))
                throw fail(400, "Invalid stage outcome");
        }
        if (kind.equals("opportunities")) {
            if (num(m, "amount", BigDecimal.ZERO).signum() < 0)
                throw fail(400, "Opportunity amount cannot be negative");
            if (!text(m, "expectedCloseDate").isEmpty())
                date(text(m, "expectedCloseDate"), "expectedCloseDate");
        }
        if (m.get("teamIds") instanceof List<?> team)
            for (Object user : team)
                activeUser(p, user.toString());
        if (kind.equals("items")) {
            String method = text(m, "valuationMethod");
            if (method.isEmpty())
                m.put("valuationMethod", "WEIGHTED_AVERAGE");
            else if (!Set.of("WEIGHTED_AVERAGE", "FIFO").contains(method))
                throw fail(400, "Choose WEIGHTED_AVERAGE or FIFO valuation");
            if (old != null) {
                Integer postings = db.queryForObject(
                        "SELECT COUNT(*) FROM stock_movements WHERE tenant_id=? AND item_id=?", Integer.class,
                        p.tenantId(), old.get("id"));
                if (postings != null && postings > 0
                        && (!currency(m).equals(text(old, "currency")) || !text(m, "unit").equals(text(old, "unit"))
                                || !text(m, "valuationMethod").equals(text(old, "valuationMethod"))
                                || !Objects.equals(m.get("serialTracked"), old.get("serialTracked"))))
                    throw fail(409, "Currency/unit/valuation/serial policy cannot change after inventory postings");
            }
        }
        if (Set.of("stockReservations", "stockAdjustments").contains(kind)) {
            get(p, "items", need(m, "itemId"));
            get(p, "warehouses", need(m, "warehouseId"));
            BigDecimal quantity = qty(num(m, "quantity", BigDecimal.ZERO));
            if (quantity.signum() == 0 || kind.equals("stockReservations") && quantity.signum() < 0)
                throw fail(400, "Invalid stock quantity");
            if (kind.equals("stockAdjustments"))
                need(m, "reason");
        }
        if (kind.equals("inventoryCounts")) {
            get(p, "warehouses", need(m, "warehouseId"));
            Set<String> seen = new HashSet<>();
            List<Map<String, Object>> lines = objects(m, "lines");
            if (lines.isEmpty())
                throw fail(400, "Count lines are required");
            for (Map<String, Object> line : lines) {
                String item = need(line, "itemId");
                get(p, "items", item);
                if (!seen.add(item) || qty(num(line, "countedQuantity", BigDecimal.valueOf(-1))).signum() < 0)
                    throw fail(400, "Count items must be unique with nonnegative countedQuantity");
            }
        }
        if (kind.equals("rfqs")) {
            for (Object supplier : m.get("supplierIds") instanceof List<?> ids ? ids : List.of())
                get(p, "suppliers", supplier.toString());
            for (Map<String, Object> line : objects(m, "lines"))
                get(p, "items", need(line, "itemId"));
        }
        if (kind.equals("supplierQuotes")) {
            Map<String, Object> rfq = get(p, "rfqs", need(m, "rfqId"));
            get(p, "suppliers", need(m, "supplierId"));
            if (rfq.get("supplierIds") instanceof List<?> suppliers && !suppliers.isEmpty()
                    && !suppliers.contains(text(m, "supplierId")))
                throw fail(400, "Supplier was not invited to this RFQ");
            for (Map<String, Object> line : objects(m, "lines"))
                if (objects(rfq, "lines").stream().noneMatch(l -> text(l, "itemId").equals(text(line, "itemId"))))
                    throw fail(400, "Supplier quoted an item outside the RFQ");
        }
        if (Set.of("supplierContracts", "customerContracts", "financialPeriods").contains(kind)) {
            LocalDate a = date(need(m, "startDate"), "startDate"), b = date(need(m, "endDate"), "endDate");
            if (b.isBefore(a))
                throw fail(400, "Dates are reversed");
            if (kind.equals("supplierContracts")) {
                get(p, "suppliers", need(m, "supplierId"));
                for (Map<String, Object> line : objects(m, "lines")) {
                    get(p, "items", need(line, "itemId"));
                    if (num(line, "unitPrice", BigDecimal.valueOf(-1)).signum() < 0)
                        throw fail(400, "Contract price cannot be negative");
                }
            }
            if (kind.equals("customerContracts"))
                get(p, "accounts", need(m, "accountId"));
            if (kind.equals("financialPeriods"))
                for (Map<String, Object> r : all(p, kind))
                    if (!text(r, "id").equals(text(m, "id")) && !a.isAfter(date(text(r, "endDate"), "endDate"))
                            && !b.isBefore(date(text(r, "startDate"), "startDate")))
                        throw fail(409, "Financial periods must not overlap");
        }
        if (Set.of("purchaseOrders", "supplierBills").contains(kind)) {
            Set<String> seen = new HashSet<>();
            for (Map<String, Object> line : objects(m, "lines")) {
                String item = need(line, "itemId");
                get(p, "items", item);
                if (!seen.add(item))
                    throw fail(400, "Combine duplicate purchase/bill item lines");
            }
        }
        if (kind.equals("supplierBills")) {
            Map<String, Object> order = get(p, "purchaseOrders", need(m, "orderId"));
            if (!text(order, "supplierId").equals(need(m, "supplierId"))
                    || !text(order, "currency").equals(currency(m)))
                throw fail(400, "Supplier bill does not match PO supplier/currency");
        }
        if (kind.equals("purchaseOrders") && !text(m, "contractId").isEmpty()) {
            Map<String, Object> contract = get(p, "supplierContracts", text(m, "contractId"));
            if (!text(contract, "supplierId").equals(text(m, "supplierId"))
                    || !currency(m).equals(text(contract, "currency")))
                throw fail(400, "Contract supplier/currency differs from purchase order");
            LocalDate today = LocalDate.now();
            if (today.isBefore(date(text(contract, "startDate"), "startDate"))
                    || today.isAfter(date(text(contract, "endDate"), "endDate")))
                throw fail(409, "Supplier contract is outside its validity period");
            for (Map<String, Object> line : objects(m, "lines")) {
                Map<String, Object> price = objects(contract, "lines").stream()
                        .filter(l -> text(l, "itemId").equals(text(line, "itemId"))).findFirst()
                        .orElseThrow(() -> fail(400, "Item has no contracted price"));
                if (money(num(line, "unitPrice", BigDecimal.ZERO))
                        .compareTo(money(num(price, "unitPrice", BigDecimal.ZERO))) != 0)
                    throw fail(409, "Purchase price does not match the valid supplier contract");
            }
        }
    }

    private void openPeriod(Principal p, LocalDate date) {
        for (Map<String, Object> period : all(p, "financialPeriods"))
            if ("CLOSED".equals(text(period, "status")) && !date.isBefore(date(text(period, "startDate"), "startDate"))
                    && !date.isAfter(date(text(period, "endDate"), "endDate")))
                throw fail(409, "Financial period is closed; authorized reopening is required");
    }

    private void purchaseBudget(Principal p, Map<String, Object> order) {
        if (text(order, "budgetId").isEmpty())
            return;
        Map<String, Object> budget = get(p, "budgets", text(order, "budgetId"));
        LocalDate today = LocalDate.now();
        if (!text(budget, "currency").equals(text(order, "currency"))
                || today.isBefore(date(text(budget, "startDate"), "startDate"))
                || today.isAfter(date(text(budget, "endDate"), "endDate")))
            throw fail(409, "Purchase budget currency/date does not match");
        BigDecimal spent = BigDecimal.ZERO;
        for (String kind : List.of("purchaseOrders", "expenses"))
            for (Map<String, Object> r : all(p, kind))
                if (text(order, "budgetId").equals(text(r, "budgetId"))
                        && Set.of("APPROVED", "PARTIALLY_RECEIVED", "RECEIVED", "SETTLED").contains(text(r, "status")))
                    spent = spent.add(num(r, "total", BigDecimal.ZERO));
        if (spent.add(num(order, "total", BigDecimal.ZERO)).compareTo(num(budget, "amount", BigDecimal.ZERO)) > 0)
            throw fail(409, "Purchase commitment exceeds remaining approved budget");
    }

    @GetMapping("/procurement/rfqs/{id}/comparison")
    public List<Map<String, Object>> quoteComparison(@RequestAttribute Principal principal, @PathVariable String id) {
        if (!domainRead(principal, "INVENTORY"))
            throw fail(403, "Procurement access is required");
        Map<String, Object> rfq = get(principal, "rfqs", id);
        return all(principal, "supplierQuotes").stream().filter(q -> id.equals(text(q, "rfqId"))).map(q -> {
            q.put("supplierName", get(principal, "suppliers", text(q, "supplierId")).get("name"));
            q.put("expired", !text(q, "expirationDate").isEmpty()
                    && date(text(q, "expirationDate"), "expirationDate").isBefore(LocalDate.now()));
            q.put("missingItems",
                    objects(rfq, "lines").stream()
                            .filter(line -> objects(q, "lines").stream()
                                    .noneMatch(quoted -> text(quoted, "itemId").equals(text(line, "itemId"))))
                            .map(line -> line.get("itemId")).toList());
            return q;
        }).sorted(Comparator.comparing(q -> num(q, "total", BigDecimal.ZERO))).toList();
    }

    @PostMapping("/supplierQuotes/{id}/award")
    @Transactional
    public Map<String, Object> awardQuote(@RequestAttribute Principal principal, @PathVariable String id) {
        domainWrite(principal, "INVENTORY");
        lock(principal, "INVENTORY", true);
        Map<String, Object> quote = get(principal, "supplierQuotes", id);
        status(quote, "DRAFT");
        notMaker(principal, quote);
        if (!text(quote, "expirationDate").isEmpty()
                && date(text(quote, "expirationDate"), "expirationDate").isBefore(LocalDate.now()))
            throw fail(409, "Supplier quote has expired");
        Map<String, Object> order = insert(principal, "purchaseOrders",
                map("name", "PO: " + text(quote, "name"), "supplierId", quote.get("supplierId"), "supplierQuoteId", id,
                        "lines", quote.get("lines"), "currency", quote.get("currency"), "total", quote.get("total")),
                "DRAFT");
        quote.put("orderId", order.get("id"));
        updateRow(principal, "supplierQuotes", quote, "AWARDED");
        return order;
    }

    @PostMapping("/supplierBills/{id}/match")
    @Transactional
    public Map<String, Object> matchSupplierBill(@RequestAttribute Principal principal, @PathVariable String id) {
        domainWrite(principal, "FINANCE");
        lock(principal, "FINANCE", false);
        openPeriod(principal, LocalDate.now());
        Map<String, Object> bill = get(principal, "supplierBills", id);
        status(bill, "DRAFT");
        notMaker(principal, bill);
        Map<String, Object> order = get(principal, "purchaseOrders", text(bill, "orderId"));
        status(order, "APPROVED", "PARTIALLY_RECEIVED", "RECEIVED");
        Map<String, Object> received = order.get("received") instanceof Map<?, ?> r ? decode(encode(r)) : map();
        for (Map<String, Object> line : objects(bill, "lines")) {
            String item = text(line, "itemId");
            Map<String, Object> ordered = objects(order, "lines").stream().filter(l -> item.equals(text(l, "itemId")))
                    .findFirst().orElseThrow(() -> fail(409, "Bill item does not exist on the purchase order"));
            if (num(ordered, "unitPrice", BigDecimal.ZERO).compareTo(num(line, "unitPrice", BigDecimal.ZERO)) != 0
                    || num(ordered, "taxRate", BigDecimal.ZERO).compareTo(num(line, "taxRate", BigDecimal.ZERO)) != 0)
                throw fail(409, "Supplier bill price/tax does not match the purchase order");
            BigDecimal previously = BigDecimal.ZERO;
            for (Map<String, Object> posted : all(principal, "supplierBills"))
                if ("MATCHED".equals(text(posted, "status")) && text(order, "id").equals(text(posted, "orderId")))
                    for (Map<String, Object> old : objects(posted, "lines"))
                        if (item.equals(text(old, "itemId")))
                            previously = previously.add(num(old, "quantity", BigDecimal.ZERO));
            if (previously.add(num(line, "quantity", BigDecimal.ZERO))
                    .compareTo(num(received, item, BigDecimal.ZERO)) > 0)
                throw fail(409, "Supplier bill exceeds accepted goods receipts after prior matched bills");
        }
        bill.put("matchedBy", principal.userId());
        bill.put("matchedAt", Instant.now().toString());
        return updateRow(principal, "supplierBills", bill, "MATCHED");
    }

    @PostMapping("/inventory/reservations/{id}/reserve")
    @Transactional
    public Map<String, Object> reserve(@RequestAttribute Principal principal, @PathVariable String id) {
        domainWrite(principal, "INVENTORY");
        lock(principal, "INVENTORY", false);
        Map<String, Object> reservation = get(principal, "stockReservations", id);
        status(reservation, "DRAFT");
        String item = text(reservation, "itemId"), warehouse = text(reservation, "warehouseId");
        BigDecimal physical = (BigDecimal) balance(principal, item, warehouse).get("quantity");
        if (physical.subtract(reserved(principal, item, warehouse))
                .compareTo(num(reservation, "quantity", BigDecimal.ZERO)) < 0)
            throw fail(409, "Insufficient unreserved stock");
        return updateRow(principal, "stockReservations", reservation, "RESERVED");
    }

    @PostMapping("/inventory/reservations/{id}/release")
    @Transactional
    public Map<String, Object> release(@RequestAttribute Principal principal, @PathVariable String id) {
        domainWrite(principal, "INVENTORY");
        lock(principal, "INVENTORY", false);
        Map<String, Object> r = get(principal, "stockReservations", id);
        status(r, "RESERVED");
        return updateRow(principal, "stockReservations", r, "RELEASED");
    }

    @PostMapping("/inventory/reservations/{id}/consume")
    @Transactional
    public Map<String, Object> consume(@RequestAttribute Principal principal, @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        domainWrite(principal, "INVENTORY");
        lock(principal, "INVENTORY", false);
        Map<String, Object> r = get(principal, "stockReservations", id);
        status(r, "RESERVED");
        Map<String, Object> result = updateRow(principal, "stockReservations", r, "CONSUMED");
        postStock(principal, text(r, "itemId"), text(r, "warehouseId"), num(r, "quantity", BigDecimal.ZERO).negate(),
                BigDecimal.ZERO, "RESERVATION_ISSUE", id, "Reserved stock consumed", "reservation:" + id,
                body == null ? map() : body);
        return result;
    }

    @PostMapping("/inventory/counts/{id}/approve")
    @Transactional
    public Map<String, Object> approveCount(@RequestAttribute Principal principal, @PathVariable String id) {
        domainWrite(principal, "INVENTORY");
        lock(principal, "INVENTORY", false);
        Map<String, Object> count = get(principal, "inventoryCounts", id);
        status(count, "DRAFT");
        notMaker(principal, count);
        String warehouse = text(count, "warehouseId");
        int n = 0;
        List<Map<String, Object>> counted = objects(count, "lines");
        for (Map<String, Object> line : counted) {
            String item = text(line, "itemId");
            Map<String, Object> before = balance(principal, item, warehouse);
            BigDecimal delta = num(line, "countedQuantity", BigDecimal.ZERO)
                    .subtract((BigDecimal) before.get("quantity"));
            if (delta.signum() != 0) {
                BigDecimal quantity = (BigDecimal) before.get("quantity"), cost = quantity.signum() == 0
                        ? num(line, "unitCost", BigDecimal.ZERO)
                        : ((BigDecimal) before.get("inventory_value")).divide(quantity, 8, RoundingMode.HALF_UP);
                postStock(principal, item, warehouse, delta, cost, "CYCLE_COUNT", id,
                        "Approved physical inventory count", "count:" + id + ":" + (n++), line);
            }
            line.put("previousQuantity", before.get("quantity"));
            line.put("difference", delta);
        }
        count.put("lines", counted);
        count.put("approvedBy", principal.userId());
        return updateRow(principal, "inventoryCounts", count, "POSTED");
    }

    @PostMapping("/inventory/adjustments/{id}/decision")
    @Transactional
    public Map<String, Object> adjustmentDecision(@RequestAttribute Principal principal, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        domainWrite(principal, "INVENTORY");
        lock(principal, "INVENTORY", false);
        Map<String, Object> r = get(principal, "stockAdjustments", id);
        status(r, "DRAFT");
        notMaker(principal, r);
        String decision = need(body, "decision");
        if (!Set.of("APPROVED", "REJECTED").contains(decision))
            throw fail(400, "Invalid decision");
        if (decision.equals("APPROVED"))
            postStock(principal, text(r, "itemId"), text(r, "warehouseId"), num(r, "quantity", BigDecimal.ZERO),
                    num(r, "unitCost", BigDecimal.ZERO), "ADJUSTMENT", id, text(r, "reason"), "adjustment:" + id, r);
        r.put("reviewedBy", principal.userId());
        return updateRow(principal, "stockAdjustments", r, decision);
    }

    @GetMapping("/inventory/traceability")
    public Map<String, Object> traceability(@RequestAttribute Principal principal) {
        if (!domainRead(principal, "INVENTORY"))
            throw fail(403, "Inventory access is required");
        return map("batches", db.queryForList(
                "SELECT id,item_id AS itemId,warehouse_id AS warehouseId,batch_code AS batch,expires_on AS expiresOn,quantity_remaining AS quantityRemaining,unit_cost AS unitCost,source_id AS sourceId FROM stock_layers WHERE tenant_id=? ORDER BY created_at",
                principal.tenantId()), "serials",
                db.queryForList(
                        "SELECT item_id AS itemId,warehouse_id AS warehouseId,serial_number AS serialNumber,status,source_id AS sourceId FROM stock_serials WHERE tenant_id=? ORDER BY serial_number",
                        principal.tenantId()));
    }

    @PostMapping("/finance/periods/{id}/close")
    @Transactional
    public Map<String, Object> closePeriod(@RequestAttribute Principal principal, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        allow(principal, "ADMIN", "FINANCE");
        lock(principal, "FINANCE", false);
        Map<String, Object> r = get(principal, "financialPeriods", id);
        status(r, "DRAFT", "OPEN");
        r.put("closeReason", need(body, "reason"));
        r.put("closedAt", Instant.now().toString());
        r.put("closedBy", principal.userId());
        return updateRow(principal, "financialPeriods", r, "CLOSED");
    }

    @PostMapping("/finance/periods/{id}/reopen")
    @Transactional
    public Map<String, Object> reopenPeriod(@RequestAttribute Principal principal, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        allow(principal, "ADMIN", "FINANCE");
        lock(principal, "FINANCE", false);
        Map<String, Object> r = get(principal, "financialPeriods", id);
        status(r, "CLOSED");
        r.put("reopenReason", need(body, "reason"));
        return updateRow(principal, "financialPeriods", r, "OPEN");
    }

    @GetMapping("/reports/finance.csv")
    public ResponseEntity<String> financeExport(@RequestAttribute Principal principal) {
        if (!domainRead(principal, "FINANCE"))
            throw fail(403, "Finance access is required");
        StringBuilder out = new StringBuilder(
                "invoice_number,customer,currency,total,paid,credited,balance,status,due_date\r\n");
        for (Map<String, Object> invoice : all(principal, "invoices"))
            if (!"DRAFT".equals(text(invoice, "status")))
                out.append(csv(text(invoice, "invoiceNumber"))).append(',')
                        .append(csv(text(get(principal, "accounts", text(invoice, "accountId")), "name"))).append(',')
                        .append(csv(text(invoice, "currency"))).append(',')
                        .append(num(invoice, "total", BigDecimal.ZERO)).append(',')
                        .append(num(invoice, "paid", BigDecimal.ZERO)).append(',')
                        .append(num(invoice, "credited", BigDecimal.ZERO)).append(',')
                        .append(num(invoice, "total", BigDecimal.ZERO).subtract(num(invoice, "paid", BigDecimal.ZERO))
                                .subtract(num(invoice, "credited", BigDecimal.ZERO)))
                        .append(',').append(csv(text(invoice, "status"))).append(',')
                        .append(csv(text(invoice, "dueDate"))).append("\r\n");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=receivables.csv").body(out.toString());
    }
}
