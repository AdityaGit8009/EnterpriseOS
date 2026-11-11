CREATE TABLE workforce_changes (
 id CHAR(36) PRIMARY KEY,
 tenant_id CHAR(36) NOT NULL,
 record_id CHAR(36) NOT NULL,
 kind VARCHAR(40) NOT NULL,
 record_version BIGINT NOT NULL,
 actor_id CHAR(36) NOT NULL,
 action VARCHAR(40) NOT NULL,
 before_value JSON NOT NULL,
 after_value JSON NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_workforce_change(record_id,record_version),
 INDEX ix_workforce_history(tenant_id,kind,record_id),
 FOREIGN KEY(tenant_id) REFERENCES tenants(id),
 FOREIGN KEY(record_id) REFERENCES business_records(id),
 FOREIGN KEY(actor_id) REFERENCES users(id)
) ENGINE=InnoDB;
