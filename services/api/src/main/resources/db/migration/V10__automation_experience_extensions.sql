ALTER TABLE workflow_instances ADD COLUMN resubmission_of CHAR(36) NULL;
ALTER TABLE document_versions ADD COLUMN storage_backend VARCHAR(10) NOT NULL DEFAULT 'LOCAL';
CREATE TABLE notification_digest_settings (
 tenant_id CHAR(36) NOT NULL,user_id CHAR(36) NOT NULL,enabled BOOLEAN NOT NULL DEFAULT FALSE,
 cadence_hours INT NOT NULL DEFAULT 24,next_due_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,user_id),FOREIGN KEY(tenant_id) REFERENCES tenants(id),FOREIGN KEY(user_id) REFERENCES users(id)
) ENGINE=InnoDB;
CREATE TABLE notification_digest_runs (
 id CHAR(36) PRIMARY KEY,tenant_id CHAR(36) NOT NULL,user_id CHAR(36) NOT NULL,
 notification_count INT NOT NULL,mail_id CHAR(36) NULL,status VARCHAR(20) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX ix_digest_user(tenant_id,user_id,created_at),FOREIGN KEY(tenant_id) REFERENCES tenants(id)
) ENGINE=InnoDB;
CREATE TABLE notification_digest_items (
 tenant_id CHAR(36) NOT NULL,user_id CHAR(36) NOT NULL,notification_id CHAR(36) NOT NULL,
 run_id CHAR(36) NOT NULL,PRIMARY KEY(tenant_id,user_id,notification_id),
 FOREIGN KEY(notification_id) REFERENCES notifications(id),FOREIGN KEY(run_id) REFERENCES notification_digest_runs(id)
) ENGINE=InnoDB;
CREATE TABLE notification_mentions (
 id CHAR(36) PRIMARY KEY,tenant_id CHAR(36) NOT NULL,author_id CHAR(36) NOT NULL,
 project_id CHAR(36) NULL,body VARCHAR(2000) NOT NULL,recipient_ids JSON NOT NULL,event_key VARCHAR(200) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX ix_mentions_tenant(tenant_id,created_at),FOREIGN KEY(tenant_id) REFERENCES tenants(id)
) ENGINE=InnoDB;
CREATE TABLE dashboard_preferences (
 tenant_id CHAR(36) NOT NULL,user_id CHAR(36) NOT NULL,widgets JSON NOT NULL,
 version BIGINT NOT NULL DEFAULT 1,updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,user_id),FOREIGN KEY(tenant_id) REFERENCES tenants(id),FOREIGN KEY(user_id) REFERENCES users(id)
) ENGINE=InnoDB;
CREATE TABLE feature_flags (
 tenant_id CHAR(36) NOT NULL,flag_key VARCHAR(40) NOT NULL,enabled BOOLEAN NOT NULL,
 description VARCHAR(500) NOT NULL DEFAULT '',version BIGINT NOT NULL DEFAULT 1,actor_id CHAR(36) NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,flag_key),FOREIGN KEY(tenant_id) REFERENCES tenants(id)
) ENGINE=InnoDB;
