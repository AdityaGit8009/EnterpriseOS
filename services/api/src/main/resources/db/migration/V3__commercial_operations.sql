CREATE TABLE business_entities (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, kind VARCHAR(40) NOT NULL,
 payload JSON NOT NULL, status VARCHAR(30) NOT NULL DEFAULT 'DRAFT', version BIGINT NOT NULL DEFAULT 1,
 total DECIMAL(19,2) NOT NULL DEFAULT 0, currency CHAR(3) NOT NULL DEFAULT 'INR',
 created_by CHAR(36) NOT NULL, updated_by CHAR(36) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX ix_business_tenant_kind(tenant_id,kind,status,created_at),
 FOREIGN KEY (tenant_id) REFERENCES tenants(id), FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB;
CREATE TABLE stock_balances (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, item_id CHAR(36) NOT NULL, warehouse_id CHAR(36) NOT NULL,
 quantity DECIMAL(19,6) NOT NULL DEFAULT 0, inventory_value DECIMAL(19,6) NOT NULL DEFAULT 0,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_stock_location(tenant_id,item_id,warehouse_id),
 FOREIGN KEY (tenant_id) REFERENCES tenants(id), FOREIGN KEY (item_id) REFERENCES business_entities(id), FOREIGN KEY (warehouse_id) REFERENCES business_entities(id)
) ENGINE=InnoDB;
CREATE TABLE stock_movements (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, item_id CHAR(36) NOT NULL, warehouse_id CHAR(36) NOT NULL,
 quantity_delta DECIMAL(19,6) NOT NULL, value_delta DECIMAL(19,6) NOT NULL,
 movement_type VARCHAR(30) NOT NULL, source_id CHAR(36), reason VARCHAR(1000) NOT NULL,
 command_key VARCHAR(180) NOT NULL, actor_id CHAR(36) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_stock_posting(tenant_id,command_key), INDEX ix_stock_ledger(tenant_id,item_id,warehouse_id,created_at),
 FOREIGN KEY (tenant_id) REFERENCES tenants(id), FOREIGN KEY (item_id) REFERENCES business_entities(id), FOREIGN KEY (warehouse_id) REFERENCES business_entities(id)
) ENGINE=InnoDB;
CREATE TABLE business_commands (
 tenant_id CHAR(36) NOT NULL, command_key VARCHAR(160) NOT NULL, request_hash CHAR(64) NOT NULL,
 response_json JSON NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,command_key), FOREIGN KEY (tenant_id) REFERENCES tenants(id)
) ENGINE=InnoDB;
CREATE TABLE finance_entries (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, invoice_id CHAR(36) NOT NULL,
 entry_type VARCHAR(20) NOT NULL, amount DECIMAL(19,2) NOT NULL, reference_text VARCHAR(1000) NOT NULL,
 actor_id CHAR(36) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX ix_invoice_entries(tenant_id,invoice_id), FOREIGN KEY (invoice_id) REFERENCES business_entities(id), FOREIGN KEY (tenant_id) REFERENCES tenants(id)
) ENGINE=InnoDB;
CREATE TABLE payroll_entries (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, run_id CHAR(36) NOT NULL, employee_id CHAR(36) NOT NULL,
 user_id CHAR(36), employee_name VARCHAR(180) NOT NULL, gross DECIMAL(19,2) NOT NULL,
 deductions DECIMAL(19,2) NOT NULL, net DECIMAL(19,2) NOT NULL, currency CHAR(3) NOT NULL, snapshot JSON NOT NULL,
 UNIQUE KEY uq_payroll_employee(tenant_id,run_id,employee_id), INDEX ix_payslip_user(tenant_id,user_id),
 FOREIGN KEY (run_id) REFERENCES business_entities(id), FOREIGN KEY (tenant_id) REFERENCES tenants(id), FOREIGN KEY (employee_id) REFERENCES business_records(id)
) ENGINE=InnoDB;
