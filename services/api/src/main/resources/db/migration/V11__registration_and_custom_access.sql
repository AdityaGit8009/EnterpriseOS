CREATE TABLE company_registrations (id CHAR(36) PRIMARY KEY, email VARCHAR(254) NOT NULL UNIQUE,
                                                                                         company_name VARCHAR(180) NOT NULL,
                                                                                                                   owner_name VARCHAR(180) NOT NULL,
                                                                                                                                           slug VARCHAR(80) NOT NULL,
                                                                                                                                                            timezone VARCHAR(80) NOT NULL,
                                                                                                                                                                                 password_hash VARCHAR(100) NOT NULL,
                                                                                                                                                                                                            token_hash CHAR(64) NOT NULL UNIQUE,
                                                                                                                                                                                                                                         expires_at DATETIME(6) NOT NULL,
                                                                                                                                                                                                                                                                verified_at DATETIME(6) NULL,
                                                                                                                                                                                                                                                                                        created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)) ENGINE=InnoDB;
CREATE TABLE tenant_domains (id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, DOMAIN VARCHAR(253) NOT NULL UNIQUE,
                                                                                                                challenge VARCHAR(100) NOT NULL,
                                                                                                                                       status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                                                                                                                                                                           expires_at DATETIME(6) NOT NULL,
                                                                                                                                                                                                  verified_at DATETIME(6) NULL,
                             FOREIGN KEY(tenant_id) REFERENCES tenants(id)) ENGINE=InnoDB;
CREATE TABLE custom_roles (id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, name VARCHAR(100) NOT NULL,
                                                                                                   base_role VARCHAR(24) NOT NULL,
                                                                                                                         permissions JSON NOT NULL,
                                                                                                                                          VERSION BIGINT NOT NULL DEFAULT 1,
                                                                                                                                                                          UNIQUE KEY uq_custom_role_name(tenant_id,

                                                                                                                                                                                         name),
                           FOREIGN KEY(tenant_id) REFERENCES tenants(id)) ENGINE=InnoDB;
ALTER TABLE memberships ADD COLUMN custom_role_id CHAR(36) NULL,
       ADD

FOREIGN KEY(custom_role_id) REFERENCES custom_roles(id);
ALTER TABLE sessions ADD COLUMN authenticated_at DATETIME(6) NULL;
ALTER TABLE sessions ADD COLUMN user_agent VARCHAR(200) NOT NULL DEFAULT '',
       ADD COLUMN client_ip_hash CHAR(64) NOT NULL DEFAULT '';
CREATE TABLE password_history (id CHAR(36) PRIMARY KEY,user_id CHAR(36) NOT NULL,password_hash VARCHAR(100) NOT NULL,
                                                                                                            created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                                                                                                                                     INDEX ix_password_history(user_id,

                                                                                                                                                               created_at),
                               FOREIGN KEY(user_id) REFERENCES users(id)) ENGINE=InnoDB;
CREATE TABLE authentication_attempts (id CHAR(36) PRIMARY KEY,email_hash CHAR(64) NOT NULL,ip_hash CHAR(64) NOT NULL,
                                                                                                            outcome VARCHAR(30) NOT NULL,
                                                                                                                                created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                                                                                                                                                         INDEX ix_auth_attempt_time(created_at)) ENGINE=InnoDB;
