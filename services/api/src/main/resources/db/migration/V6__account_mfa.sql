CREATE TABLE user_mfa (
 user_id CHAR(36) PRIMARY KEY,
 encrypted_secret VARCHAR(500) NOT NULL DEFAULT '',
 pending_secret VARCHAR(500) NOT NULL DEFAULT '',
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 last_counter BIGINT NOT NULL DEFAULT -1,
 FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;
CREATE TABLE mfa_recovery_codes (
 user_id CHAR(36) NOT NULL,
 code_hash CHAR(64) NOT NULL,
 used_at DATETIME(6) NULL,
 PRIMARY KEY(user_id,code_hash),
 FOREIGN KEY(user_id) REFERENCES users(id)
) ENGINE=InnoDB;
