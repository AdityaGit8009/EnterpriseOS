# SolDrashta operations

## Local installation

Double-click `Start-EnterpriseOS.cmd`, or run `./Start-EnterpriseOS.ps1` from the repository. Node 22+, Java 25+ and MySQL 8.4 binaries are required. On this workstation the launcher discovers the installed Wamp MySQL binaries, but creates an independent data directory and uses port 3309. It does not access Wamp's existing databases.

Open http://localhost:5173. First-run setup creates the company and two separate accounts: the company owner and platform administrator. The owner email and company name are prefilled from the user's instructions; passwords must be entered privately. No employees, customers, balances, stock or payroll records are invented. The platform account manages companies and subscriptions; the owner account manages business operations. Create a second authorized approver for maker/checker workflows.

The first-run endpoint is available only when `APP_SETUP_ENABLED=true` and the users table is empty. Initialization is serialized using a database row lock. For an internet deployment, provision the first accounts on a restricted network and disable setup before exposure. The local launcher binds the API, frontend and database to loopback.

The launcher persists service PIDs and checks process identity before shutdown. `./Stop-EnterpriseOS.ps1` stops its managed services and retains data. Logs are under `var/runtime`. Database credentials and the persistent MFA encryption key are in `var/runtime/settings.json`, excluded from source control. Limit this directory to the workstation owner; the application must not serve it. Never delete it to reset an account.

## Mail and integrations

SMTP configuration uses `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD` and `APP_MAIL_FROM`. Without SMTP, messages are written to the private local outbox, not delivered to email recipients. Local invitation/reset links can be opened from those `.eml` files. Production must configure a verified sender and delivery monitoring. Do not expose the outbox over HTTP.

Third-party integrations require a real receiver URL and credentials. Configure only intended destinations, rotate credentials, inspect delivery/retry results and test failed callbacks. No bank transfers or payment gateway charges are executed by the application. Bank exports are files for separate review and authorized upload.

## Authenticator MFA

Company registration is separate from first-run installation. Once installation exists, a new company can submit registration and consume its single-use email link. No company account is created until email verification succeeds. Without SMTP, that link remains in the private outbox. A domain claim requires control of the domain's DNS: add the exact `_enterpriseos.<domain>` TXT value shown by the company settings screen, then verify before the 24-hour challenge expires. A Gmail address is an owner contact address, not a company-owned domain.

Custom roles select allowed server method/route actions and a non-administrator base role. Both restrictions apply, along with existing tenant and record visibility. Include the read dependencies needed by each selected workflow. Changes apply to current sessions immediately; assigning a custom role revokes that member's existing integration credentials. Keep at least one active unrestricted company administrator. Individual sessions or all sessions can be revoked from the account screen. Session renewal rotates the cookie but still requires a fresh sign-in after 24 hours.

The account screen can provision an authenticator secret and enable TOTP using a six-digit code. Save the one-time recovery codes privately. Secrets are encrypted with AES-256-GCM using `APP_ENCRYPTION_KEY`. The native launcher generates this key once. Docker deployments must supply a persistent random 64-hex key. Changing or losing it prevents existing authenticator secrets from decrypting; restore the original key from an encrypted backup. Recovery codes are stored as hashes, consumed transactionally and never shown again. Accepted time steps cannot be replayed.

## Encrypted local backup and restoration drill

Pause application writes while leaving MySQL running:

```powershell
$backupSecret = Read-Host 'Backup passphrase (16+ characters)' -AsSecureString
$env:EOS_BACKUP_PASSPHRASE = [System.Net.NetworkCredential]::new('', $backupSecret).Password
.\Backup-EnterpriseOS.ps1
.\Backup-EnterpriseOS.ps1 -VerifyFile 'D:/EP/EOS/var/backups/REPLACE-WITH-BACKUP.eosbackup'
Remove-Item Env:EOS_BACKUP_PASSPHRASE
.\Start-EnterpriseOS.ps1
```

Backups contain a consistent logical database dump, document/logo assets and the application encryption key. They use scrypt-derived AES-256-GCM authenticated encryption. Store the passphrase separately. Verification imports into a newly named isolated schema, compares every table row count, verifies asset hashes, and writes measured evidence under `var/runtime`. It never overwrites the live schema. A successful drill does not imply any contractual RPO/RTO. The local utility has a 512 MiB dump limit; larger deployments require a managed streaming backup system and object-storage snapshots.

The verification schema is deliberately retained for review. For real recovery, stop writes, preserve the failed environment, restore into a separate MySQL instance/schema, restore assets and the encryption key, run tenant-isolation and business reconciliation checks, then change application configuration to the recovered instance. Do not drop a live database as a recovery shortcut. Define encrypted off-host schedules, retention and alerting before deployment.

## Release and incident procedure

1. Run frontend typecheck/tests/build and API `mvn verify` against a dedicated test database. CI runs these checks, archives evidence and checks production npm dependencies.
2. Back up the database and assets and perform an isolated restore drill. Record the release version, migration versions, configuration differences and verification results.
3. Deploy to staging with production-like configuration and synthetic records. Verify tenant isolation, approvals, financial totals, uploads, invitation/reset/MFA, subscriptions and backup recovery.
4. Release only after authorized UAT approval. Keep the preceding application artifact and backups. Database migrations are forward-only; rolling back binaries requires schema compatibility. Restore to a separate database when a destructive migration cannot be reversed safely.
5. For a security incident, restrict ingress, revoke affected sessions/credentials, preserve logs and audit evidence, identify affected tenants, restore trusted configuration, validate recovery, then record cause and corrective actions.

`/health` checks database reachability. `/api/v1/platform/runtime-metrics` provides authenticated process request/error/duration counters. HTTP logs contain request IDs, route templates, statuses and duration; no request bodies or query tokens. Aggregate logs, external alerting, TLS termination, secret-manager integration and workload-specific capacity targets must be configured for the selected hosting environment. Local process counters do not replace a central monitoring service.

## India configuration

The selected starting values are INR, India, `Asia/Kolkata`, `en-IN` and an April fiscal-year start. These are editable company settings. Enter the actual registered address, GSTIN, invoice numbering policy, tax categories and place-of-supply rules before issuing business invoices. Payroll components and deductions require approved company-specific examples; no statutory payroll or filing certification is implied.

Primary references for reviewing the localized policy are [CBIC Rule 46](https://taxinformation.cbic.gov.in/content-page/explore-rules/1000136/1000001) for invoice particulars and the [EPFO official website](https://www.epfindia.gov.in/) for applicable employer rules. Configuration must be checked against current applicability rather than copying a universal contribution or tax rate.
