CREATE TABLE stock_layers (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, item_id CHAR(36) NOT NULL, warehouse_id CHAR(36) NOT NULL,
 batch_code VARCHAR(120) NOT NULL, expires_on DATE NULL, quantity_remaining DECIMAL(19,6) NOT NULL,
 unit_cost DECIMAL(19,6) NOT NULL, source_id CHAR(36), created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX ix_fifo_layers(tenant_id,item_id,warehouse_id,created_at,id),
 INDEX ix_batch_layers(tenant_id,item_id,batch_code), FOREIGN KEY (tenant_id) REFERENCES tenants(id)
) ENGINE=InnoDB;
CREATE TABLE stock_serials (
 id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, item_id CHAR(36) NOT NULL, warehouse_id CHAR(36) NOT NULL,
 serial_number VARCHAR(120) NOT NULL, status VARCHAR(20) NOT NULL, source_id CHAR(36),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_tenant_serial(tenant_id,serial_number),
 INDEX ix_serial_stock(tenant_id,item_id,warehouse_id,status), FOREIGN KEY (tenant_id) REFERENCES tenants(id)
) ENGINE=InnoDB;

INSERT INTO stock_layers(id,tenant_id,item_id,warehouse_id,batch_code,quantity_remaining,unit_cost)
 SELECT UUID(),tenant_id,item_id,warehouse_id,'MIGRATED',quantity,inventory_value/quantity FROM stock_balances WHERE quantity>0;
