package com.enterpriseos;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationEvents {
    private final JdbcTemplate db;

    public AuthenticationEvents(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String email, String ip, String outcome) {
        db.update("INSERT INTO authentication_attempts(id,email_hash,ip_hash,outcome) VALUES (?,?,?,?)",
                UUID.randomUUID().toString(), Tokens.hash(email), Tokens.hash(ip), outcome);
    }
}
