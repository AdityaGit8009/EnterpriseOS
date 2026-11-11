# EnterpriseOS

EnterpriseOS is a multi-tenant business operations application with a React and TypeScript web client, a Java 25 / Spring Boot API, and MySQL persistence. The repository includes application source, database migrations, deployment examples, and verification evidence.

<p>
  <a href="https://github.com/AdityaGit8009/EnterpriseOS/actions/workflows/verify.yml"><img src="https://github.com/AdityaGit8009/EnterpriseOS/actions/workflows/verify.yml/badge.svg?branch=main" alt="Verification workflow status"></a>
  <img src="https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white" alt="Java 25">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 3.5">
  <img src="https://img.shields.io/badge/React-19-149ECA?logo=react&logoColor=white" alt="React 19">
  <img src="https://img.shields.io/badge/MySQL-8.4-4479A1?logo=mysql&logoColor=white" alt="MySQL 8.4">
</p>

> **Project status:** This is an actively developed project, not a claim of production readiness. The current feature inventory records 226 capabilities as implemented, 87 as partial, and 3 as requiring external configuration. Production deployment, customer acceptance, and regulatory certification have not been completed.

## What it covers

The application organizes functionality into five business areas:

1. **Organization and access** — tenant setup, membership, roles, invitations, audit history, MFA, and account security.
2. **People and work** — employee lifecycle, attendance, leave, projects, tasks, approvals, and time tracking.
3. **Business operations** — CRM, purchasing, inventory, invoicing, expenses, and payroll workflows.
4. **Automation and records** — configurable workflows, documents, reports, integrations, and webhooks.
5. **Administration and recovery** — subscriptions, operational controls, encrypted backups, and restore verification.

Feature-level status is tracked in the [implementation matrix](docs/FEATURE_IMPLEMENTATION_MATRIX.csv), [Phase 3 coverage](docs/phase3-coverage.csv), and [Phase 4–5 coverage](docs/phase4-5-coverage.csv).

## Technology

| Component | Technology |
| --- | --- |
| Web client | React, TypeScript, Vite |
| API | Java 25, Spring Boot 3.5 |
| Database | MySQL 8.4 |
| Schema management | Flyway |
| API contract | [OpenAPI](docs/api/openapi.json) |

## Run locally

### Requirements

- Node.js 22 or later
- Java 25 or later
- MySQL 8.4
- Maven 3.9 or later for backend builds

On Windows, run `Start-EnterpriseOS.cmd` or `.\Start-EnterpriseOS.ps1` from the project directory. The launcher starts the frontend at [http://localhost:5173](http://localhost:5173), the API on port `8080`, and an isolated local MySQL data directory on port `3309`. Set `MYSQL_BIN` if the MySQL 8.4 binaries are not discoverable.

For the first run, create the organization owner and platform administrator through the setup screen using new, private passwords. No default business accounts or sample business records are created.

To stop the local services while keeping their data, run `.\Stop-EnterpriseOS.ps1`.

### Docker

Copy `.env.example` to `.env`, set unique local credentials and a persistent encryption key, then run:

```powershell
docker compose up --build
```

Keep `.env` and all generated runtime data private. Never commit real passwords, keys, or production configuration.

## Build and test

Frontend, from `apps/web`:

```powershell
npm install
npm run typecheck
npm test
npm run build
```

Backend, from `services/api`, using Java 25:

```powershell
mvn verify
```

Backend integration tests use Testcontainers with Docker or an explicitly configured **isolated test database**. Do not point tests at a working company or production database.

The latest checked-in [verification summary](IMPLEMENTATION_STATUS.txt) reports 86 backend tests and 47 frontend tests passing, along with local HTTP and recovery checks. Browser-based visual/E2E testing and Docker deployment were not verified in that report. See the [machine-readable evidence](docs/VERIFICATION.json) for details and scope.

## Configuration and operational notes

- Without SMTP configuration, invitation and password-reset messages are written to a local outbox and are not delivered to recipients.
- Payroll and tax workflows require organization-specific policies and expert review; the application does not file returns or execute bank transfers.
- Document scanning requires ClamAV or an independent review process. Unscanned documents remain quarantined.
- Production use requires environment-specific HTTPS, secret management, backups, monitoring, integration credentials, and deployment validation.
- The application has not been certified for regulatory compliance or customer production use.

See the [operations guide](docs/OPERATIONS.md) for setup, security, and recovery guidance, and the [API guide](services/api/README.md) for backend configuration.

## Security

Do not disclose vulnerabilities publicly. If private vulnerability reporting is enabled for this repository, use GitHub's private reporting flow; otherwise, contact the maintainers through their GitHub account.
