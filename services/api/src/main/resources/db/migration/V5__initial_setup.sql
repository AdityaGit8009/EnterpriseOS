CREATE TABLE setup_guard (id INT PRIMARY KEY, completed_at DATETIME(6) NULL) ENGINE=InnoDB;
INSERT INTO setup_guard(id)

VALUES (1);
ALTER TABLE memberships
DROP CHECK memberships_chk_1;
ALTER TABLE memberships ADD
CONSTRAINT memberships_role_allowed CHECK (ROLE IN ('ADMIN','HR','MANAGER',
                                                                                'MEMBER','AUDITOR','SALES',
                                                                                'PROCUREMENT','FINANCE','PAYROLL'));
ALTER TABLE invitations
DROP CHECK invitations_chk_1;
ALTER TABLE invitations ADD
CONSTRAINT invitations_role_allowed CHECK (ROLE IN ('ADMIN','HR','MANAGER',
                                                                                'MEMBER','AUDITOR','SALES',
                                                                                'PROCUREMENT','FINANCE','PAYROLL'));
ALTER TABLE organization_profiles ADD COLUMN currency CHAR(3) NOT NULL DEFAULT 'INR',
       ADD COLUMN country CHAR(2) NOT NULL DEFAULT 'IN',

       ADD COLUMN locale VARCHAR(35) NOT NULL DEFAULT 'en-IN',

       ADD COLUMN fiscal_year_start_month INT NOT NULL DEFAULT 4,

       ADD COLUMN gstin VARCHAR(15) NOT NULL DEFAULT '',

       ADD
CONSTRAINT fiscal_month_valid CHECK (fiscal_year_start_month BETWEEN 1 AND 12);
