package com.enterpriseos;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class WorkflowFormService {
    private final AutomationSupport s;

    public WorkflowFormService(AutomationSupport s) {
        this.s = s;
    }

    public List<Map<String, Object>> fields(Object input) {
        if (input == null)
            return List.of();
        List<Map<String, Object>> raw = s.mapList(input);
        if (raw.size() > 30)
            throw new ApiException(400, "A workflow form supports at most 30 typed fields.");
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var field : raw) {
            String key = Inputs.text(field, "key", 40), label = Inputs.text(field, "label", 100),
                    type = Inputs.text(field, "type", 10);
            if (!key.matches("[A-Za-z][A-Za-z0-9_]{0,39}")
                    || Set.of("tenantId", "tenant_id", "role", "userId", "password", "secret", "permissions", "class")
                            .contains(key)
                    || !seen.add(key))
                throw new ApiException(400, "Use unique form keys without reserved system fields or property paths.");
            if (!Set.of("TEXT", "NUMBER", "DATE", "SELECT").contains(type))
                throw new ApiException(400, "Form field type must be TEXT, NUMBER, DATE or SELECT.");
            if (field.get("required") != null && !(field.get("required") instanceof Boolean))
                throw new ApiException(400, "Form required must be a boolean.");
            Map<String, Object> normalized = new LinkedHashMap<>(Map.of("key", key, "label", label, "type", type,
                    "required", Boolean.TRUE.equals(field.get("required"))));
            if (type.equals("TEXT"))
                normalized.put("maxLength", s.integer(field, "maxLength", 500, 1, 2000));
            if (type.equals("NUMBER")) {
                if (field.get("min") != null)
                    normalized.put("min", s.decimal(field, "min", BigDecimal.ZERO));
                if (field.get("max") != null)
                    normalized.put("max", s.decimal(field, "max", BigDecimal.ZERO));
                if (normalized.get("min") instanceof BigDecimal min && normalized.get("max") instanceof BigDecimal max
                        && min.compareTo(max) > 0)
                    throw new ApiException(400, "Form number bounds are invalid.");
            }
            if (type.equals("SELECT")) {
                if (!(field.get("options") instanceof List<?> options) || options.isEmpty() || options.size() > 50
                        || options.stream()
                                .anyMatch(v -> !(v instanceof String text) || text.isBlank() || text.length() > 100))
                    throw new ApiException(400, "Select fields need 1–50 nonempty text options.");
                normalized.put("options", new LinkedHashSet<>(options).stream().toList());
            }
            result.add(normalized);
        }
        return result;
    }

    public Map<String, Object> values(Object schema, Object raw) {
        List<Map<String, Object>> fields = fields(schema);
        Map<String, Object> input;
        if (raw == null)
            input = Map.of();
        else if (raw instanceof Map<?, ?> map) {
            input = new LinkedHashMap<>();
            map.forEach((k, v) -> input.put(k.toString(), v));
        } else
            throw new ApiException(400, "formData must be an object.");
        Set<String> keys = new HashSet<>();
        fields.forEach(f -> keys.add(f.get("key").toString()));
        if (!keys.containsAll(input.keySet()))
            throw new ApiException(400, "Form contains fields not defined in the published workflow.");
        Map<String, Object> output = new LinkedHashMap<>();
        for (var field : fields) {
            String key = field.get("key").toString();
            Object value = input.get(key);
            if (value == null || value instanceof String text && text.isBlank()) {
                if (Boolean.TRUE.equals(field.get("required")))
                    throw new ApiException(400, field.get("label") + " is required.");
                continue;
            }
            switch (field.get("type").toString()) {
            case "TEXT" -> {
                if (!(value instanceof String text) || text.length() > ((Number) field.get("maxLength")).intValue())
                    throw new ApiException(400, field.get("label") + " exceeds its text limit.");
                output.put(key, text.trim());
            }
            case "NUMBER" -> {
                BigDecimal number = s.decimal(Map.of("value", value), "value", null);
                if (field.get("min") instanceof BigDecimal min && number.compareTo(min) < 0
                        || field.get("max") instanceof BigDecimal max && number.compareTo(max) > 0)
                    throw new ApiException(400, field.get("label") + " is outside the allowed range.");
                output.put(key, number);
            }
            case "DATE" -> {
                try {
                    output.put(key, LocalDate.parse(value.toString()).toString());
                } catch (Exception ex) {
                    throw new ApiException(400, field.get("label") + " must be an ISO date.");
                }
            }
            case "SELECT" -> {
                if (!(field.get("options") instanceof List<?> options) || !options.contains(value))
                    throw new ApiException(400, field.get("label") + " must match an allowed option.");
                output.put(key, value);
            }
            default -> throw new ApiException(400, "Unsupported form field type.");
            }
        }
        return output;
    }

    public Map<String, Object> instanceSnapshot(Object published, List<Map<String, Object>> steps, Object data) {
        Map<String, Object> definition = s.decode(published);
        var schema = fields(definition.get("formFields"));
        return new LinkedHashMap<>(Map.of("steps", steps, "formFields", schema, "formData", values(schema, data)));
    }

    public Map<String, Object> replaceSteps(Object snapshot, List<Map<String, Object>> steps) {
        var value = s.decode(snapshot);
        value.put("steps", steps);
        return value;
    }
}
