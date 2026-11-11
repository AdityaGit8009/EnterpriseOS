package com.enterpriseos;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class FeatureFlagService {
    private final JdbcTemplate db;

    public FeatureFlagService(JdbcTemplate db) {
        this.db = db;
    }

    public boolean enabled(String tenant, String key) {
        var rows = db.queryForList("SELECT enabled FROM feature_flags WHERE tenant_id=? AND flag_key=?", tenant, key);
        if (rows.isEmpty())
            return true;
        Object value = rows.getFirst().get("enabled");
        return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() != 0;
    }

    public void require(String tenant, String key) {
        if (!enabled(tenant, key))
            throw new ApiException(403, "The organization administrator disabled this feature: " + key);
    }
}
