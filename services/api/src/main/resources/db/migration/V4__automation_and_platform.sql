CREATE TABLE workflow_definitions (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, name VARCHAR(180) NOT NULL,
 description VARCHAR(1000) NOT NULL DEFAULT '', draft JSON NOT NULL,
 published_version INT NOT NULL DEFAULT 0, row_version BIGINT NOT NULL DEFAULT 1,
 created_by CHAR(36) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_workflow_name(tenant_id,name), FOREIGN KEY (tenant_id) REFERENCES tenants(id)
) ENGINE=InnoDB;
CREATE TABLE workflow_versions (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, definition_id CHAR(36) NOT NULL,
 version INT NOT NULL, snapshot JSON NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_workflow_version(definition_id,version), FOREIGN KEY (definition_id) REFERENCES workflow_definitions(id)
) ENGINE=InnoDB;
CREATE TABLE workflow_instances (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, definition_version_id CHAR(36) NOT NULL,
 requester_id CHAR(36) NOT NULL, title VARCHAR(180) NOT NULL, amount DECIMAL(18,2) NOT NULL DEFAULT 0,
 state VARCHAR(20) NOT NULL DEFAULT 'PENDING', current_step INT NOT NULL DEFAULT 0,
 assignment_snapshot JSON NOT NULL, row_version BIGINT NOT NULL DEFAULT 1,
 due_at DATETIME(6) NOT NULL, escalated BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX ix_workflow_tenant(tenant_id,state), FOREIGN KEY (definition_version_id) REFERENCES workflow_versions(id)
) ENGINE=InnoDB;
CREATE TABLE workflow_actions (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, instance_id CHAR(36) NOT NULL,
 step_number INT NOT NULL, actor_id CHAR(36) NOT NULL, action VARCHAR(30) NOT NULL,
 reason VARCHAR(1000) NOT NULL DEFAULT '', created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_workflow_actor_step(instance_id,step_number,actor_id,action),
 FOREIGN KEY (instance_id) REFERENCES workflow_instances(id)
) ENGINE=InnoDB;
CREATE TABLE notifications (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, user_id CHAR(36) NOT NULL,
 event_key VARCHAR(200) NOT NULL, title VARCHAR(180) NOT NULL, body VARCHAR(2000) NOT NULL,
 read_at DATETIME(6) NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_notification_event(tenant_id,user_id,event_key), INDEX ix_notifications_user(tenant_id,user_id,created_at),
 FOREIGN KEY (tenant_id) REFERENCES tenants(id), FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;
CREATE TABLE notification_preferences (
 tenant_id CHAR(36) NOT NULL, user_id CHAR(36) NOT NULL, event_type VARCHAR(100) NOT NULL,
 in_app BOOLEAN NOT NULL DEFAULT TRUE, email BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(tenant_id,user_id,event_type), FOREIGN KEY (tenant_id) REFERENCES tenants(id)
) ENGINE=InnoDB;
CREATE TABLE notification_deliveries (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, user_id CHAR(36) NOT NULL,
 event_key VARCHAR(200) NOT NULL, channel VARCHAR(20) NOT NULL, status VARCHAR(30) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), INDEX ix_notification_delivery(tenant_id,user_id,created_at)
) ENGINE=InnoDB;
CREATE TABLE documents (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, owner_id CHAR(36) NOT NULL,
 name VARCHAR(180) NOT NULL, category VARCHAR(80) NOT NULL, visibility VARCHAR(20) NOT NULL,
 link_kind VARCHAR(30) NOT NULL DEFAULT '', link_id VARCHAR(36) NOT NULL DEFAULT '',
 current_version INT NOT NULL DEFAULT 1, retention_until DATE NULL, legal_hold BOOLEAN NOT NULL DEFAULT FALSE, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX ix_documents_tenant(tenant_id,status), FOREIGN KEY (tenant_id) REFERENCES tenants(id)
) ENGINE=InnoDB;
CREATE TABLE document_versions (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, document_id CHAR(36) NOT NULL,
 version INT NOT NULL, storage_key VARCHAR(100) NOT NULL UNIQUE, mime VARCHAR(100) NOT NULL,
 byte_size BIGINT NOT NULL, sha256 CHAR(64) NOT NULL, scan_status VARCHAR(30) NOT NULL DEFAULT 'PENDING_REVIEW',
 scan_evidence VARCHAR(1000) NOT NULL DEFAULT '', created_by CHAR(36) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_document_version(document_id,version), FOREIGN KEY (document_id) REFERENCES documents(id)
) ENGINE=InnoDB;
CREATE TABLE document_access_events (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, document_id CHAR(36) NOT NULL,
 actor_id CHAR(36) NOT NULL, action VARCHAR(30) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (document_id) REFERENCES documents(id)
) ENGINE=InnoDB;
CREATE TABLE document_download_links (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, document_id CHAR(36) NOT NULL,
 user_id CHAR(36) NOT NULL, token_hash CHAR(64) NOT NULL UNIQUE, expires_at DATETIME(6) NOT NULL,
 FOREIGN KEY (document_id) REFERENCES documents(id)
) ENGINE=InnoDB;
CREATE TABLE report_definitions (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, owner_id CHAR(36) NOT NULL,
 name VARCHAR(180) NOT NULL, kind VARCHAR(40) NOT NULL, filters JSON NOT NULL,
 cadence_hours INT NOT NULL DEFAULT 0, next_run_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), FOREIGN KEY (tenant_id) REFERENCES tenants(id)
) ENGINE=InnoDB;
CREATE TABLE report_exports (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, owner_id CHAR(36) NOT NULL,
 definition_id CHAR(36) NULL, name VARCHAR(180) NOT NULL, kind VARCHAR(40) NOT NULL,
 content MEDIUMTEXT NOT NULL, row_manifest JSON NOT NULL, row_count INT NOT NULL, status VARCHAR(20) NOT NULL,
 error_message VARCHAR(500) NOT NULL DEFAULT '',
 expires_at DATETIME(6) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (tenant_id) REFERENCES tenants(id)
) ENGINE=InnoDB;
CREATE TABLE webhook_endpoints (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, name VARCHAR(180) NOT NULL,
 url VARCHAR(1000) NOT NULL, secret VARCHAR(100) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', direction VARCHAR(20) NOT NULL DEFAULT 'OUTBOUND',
 events JSON NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (tenant_id) REFERENCES tenants(id)
) ENGINE=InnoDB;
CREATE TABLE webhook_deliveries (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, endpoint_id CHAR(36) NOT NULL,
 event_id CHAR(36) NOT NULL, event_type VARCHAR(100) NOT NULL, payload JSON NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NOT NULL, response_code INT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), delivered_at DATETIME(6) NULL,
 UNIQUE KEY uq_webhook_event(endpoint_id,event_id), INDEX ix_webhook_dispatch(status,next_attempt_at),
 FOREIGN KEY (endpoint_id) REFERENCES webhook_endpoints(id)
) ENGINE=InnoDB;
CREATE TABLE api_credentials (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, name VARCHAR(180) NOT NULL,
 token_hash CHAR(64) NOT NULL UNIQUE, scopes JSON NOT NULL, created_by CHAR(36) NOT NULL, revoked_at DATETIME(6) NULL,
 expires_at DATETIME(6) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (tenant_id) REFERENCES tenants(id)
) ENGINE=InnoDB;
CREATE TABLE integration_inbound_events (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, endpoint_id CHAR(36) NOT NULL,
 event_id VARCHAR(100) NOT NULL, payload JSON NOT NULL, payload_hash CHAR(64) NOT NULL,
 received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_inbound_replay(endpoint_id,event_id), FOREIGN KEY (endpoint_id) REFERENCES webhook_endpoints(id)
) ENGINE=InnoDB;
CREATE TABLE subscription_plans (
 id CHAR(36) PRIMARY KEY, name VARCHAR(180) NOT NULL UNIQUE, seat_limit INT NOT NULL,
 record_limit INT NOT NULL, modules JSON NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
CREATE TABLE tenant_subscriptions (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL UNIQUE, plan_id CHAR(36) NOT NULL,
 status VARCHAR(20) NOT NULL, expires_at DATETIME(6) NOT NULL, grace_until DATETIME(6) NULL,
 version BIGINT NOT NULL DEFAULT 1, billing_contact VARCHAR(254) NOT NULL DEFAULT '',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (tenant_id) REFERENCES tenants(id), FOREIGN KEY (plan_id) REFERENCES subscription_plans(id)
) ENGINE=InnoDB;
CREATE TABLE subscription_events (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, subscription_id CHAR(36) NOT NULL,
 actor_id CHAR(36) NOT NULL, before_state JSON NOT NULL, after_state JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), FOREIGN KEY (subscription_id) REFERENCES tenant_subscriptions(id)
) ENGINE=InnoDB;
CREATE TABLE platform_records (
 id CHAR(36) PRIMARY KEY, kind VARCHAR(40) NOT NULL, payload JSON NOT NULL,
 status VARCHAR(30) NOT NULL, version BIGINT NOT NULL DEFAULT 1, actor_id CHAR(36) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX ix_platform_kind(kind,status)
) ENGINE=InnoDB;
