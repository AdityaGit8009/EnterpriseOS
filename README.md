# SolDrashta · EnterpriseOS

This is a real React, Spring Boot and MySQL application covering all five phase module groups. The frontend requires authenticated API access. There is no sample-data mode or client-side role impersonation.

## Start the application

Double-click **Start-EnterpriseOS.cmd**, or run `./Start-EnterpriseOS.ps1` from `D:\EP\EOS`. Open **http://localhost:5173**.

First-run setup is prefilled with **SolDrashta**, owner email **adityapratap7391@gmail.com**, and **Asia/Kolkata**. Enter a private ERP password and a separate platform administrator email/password. These are new ERP passwords, not your Gmail password. No business records, employees, balances or stock are invented.

The launcher uses installed Node 22+, Java 25+ and MySQL 8.4 binaries. It creates a separate database at `var/runtime/mysql` on port **3309**, API on **8080**, and frontend on **5173**. Existing Wamp databases are not used. Set `MYSQL_BIN` if MySQL is installed elsewhere.

Run `./Stop-EnterpriseOS.ps1` to stop while preserving data. Logs and private runtime settings are under `var/runtime`, excluded from source control.

Opening `erp-home (1).html` directly now shows server-start guidance. Real authentication requires the HTTP-served application. The original theme reference remains in `references/erp-home.original.html`.

## Module groups

| Phase | Implemented module groups |
| --- | --- |
| 1 | Company setup/platform tenants, email-verified registration, DNS domain ownership, India settings/logo, password history/session rotation, TOTP MFA/recovery codes, invitations/import, predefined/custom action roles and audit |
| 2 | Employees/lifecycle/checklists/auto IDs, private HR fields, shifts/attendance/corrections, leave/ledger/approvals, resignation/comp-off/encashment eligibility, projects/tasks/sprints/Kanban, recurring tasks/risks, time review and change history |
| 3 | CRM/forecasts/quotes/orders, RFQs/purchases/receipts/returns, stock/FIFO/lots/serials/reservations/counts, invoices/GST/payment records/credits, budgets/expenses, payroll/payslips and fiscal locks |
| 4 | Versioned workflows/typed forms/dry runs, mentions/digests/SSE, controlled documents/previews/optional object storage, saved/scheduled CSV reports/widgets, scoped API credentials and signed webhooks/retries |
| 5 | Plans/subscriptions/quotas, operational/release records, module feature flags, security/metrics, CI definition, encrypted backup and isolated restore verification |

The right-middle appearance button provides **10 color combinations**, light/dark mode and saved preferences. Maker/checker workflows require a different authorized reviewer; invite a second appropriate administrator or domain reviewer.

## Coverage and required configuration

The source catalog contains **316 proposed capabilities**, including advanced items. Module coverage does not mean every catalog item is complete. See [all-feature matrix](docs/FEATURE_IMPLEMENTATION_MATRIX.csv), [Phase 3 coverage](docs/phase3-coverage.csv), and [Phase 4/5 coverage](docs/phase4-5-coverage.csv) for implemented, partial, configuration-dependent and missing features. Production certification and completed client UAT are not claimed.

Current review: **226 implemented, 87 partial, 3 requiring external configuration**. Verification covers **86 distinct backend tests**, **47 frontend tests**, **36 HTTP checks**, a **1,200-request local workload with zero failures**, and an encrypted **68-table isolated restore**. See [verification evidence](docs/VERIFICATION.json) for scope and limitations.

- Without SMTP, invitations/reset emails go to the private local `.eml` outbox. They are not delivered to Gmail until a real sender is configured.
- India tax/payroll values require actual company registrations, applicable rules and approved examples. No tax filing, statutory certification or bank transfer execution is provided.
- Documents require ClamAV or independent manual review before download; files are not falsely marked malware-free.
- External integration destinations, hosting/TLS, centralized alerting and production backup schedules need real environment configuration.
- Browser visual/E2E verification was blocked by browser-access permissions; automated checks are documented separately.

## Build and verify

Frontend, from `apps/web`: `npm install`, `npm run typecheck`, `npm test`, `npm run build`.

Backend, from `services/api`: `mvn verify` using Java 25+. Spring Boot is **3.5.16**. Integration tests require Docker/Testcontainers or explicit `TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD` for an isolated test database. Never test against the company database. The first-install test creates its own isolated schema.

After rebuilding, stop and restart the launcher. It copies the packaged API to a private runtime artifact. Docker alternative: configure `.env` from `.env.example`, then `docker compose up --build`; Docker verification is separate from native Windows verification.

See [operations and encrypted recovery](docs/OPERATIONS.md), [API setup](services/api/README.md), [OpenAPI](docs/api/openapi.json), and [verification status](IMPLEMENTATION_STATUS.txt). Original requirements/images remain unchanged; older Phase 1/2 review files are historical planning records.
