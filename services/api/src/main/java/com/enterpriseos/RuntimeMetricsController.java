package com.enterpriseos;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class RuntimeMetricsController {
    private final RequestTelemetryFilter metrics;

    public RuntimeMetricsController(RequestTelemetryFilter metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/api/v1/platform/runtime-metrics")
    public Map<String, Object> metrics(@RequestAttribute Principal principal) {
        Inputs.requireRole(principal, "PLATFORM_ADMIN");
        return metrics.snapshot();
    }
}
