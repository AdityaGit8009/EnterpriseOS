# API lifecycle and compatibility

The supported API major version is `/api/v1`. The machine-readable contract is `docs/api/openapi.json`. Adding optional fields/endpoints is compatible; removing fields, narrowing accepted values, changing money semantics or weakening authorization is a breaking change and must use a new major version or an explicitly reviewed migration.

No current endpoint is deprecated. A future deprecation must include an inventory of actual consumers, migration documentation, a published removal date and parallel support for the replacement. During the agreed transition, the affected response must emit the standardized `Deprecation` header and a `Link` to the migration guide; use `Sunset` only after a removal date is approved. Do not advertise a fictitious sunset date. Contract tests for existing consumers remain in CI until migration is accepted.

Financial idempotency keys belong to the authenticated tenant and operation. Retries must preserve both key and request payload. Authentication is normally an HttpOnly cookie; scoped integration credentials are separately issued, revocable, expiring read credentials. Changing a user to a restricted custom role revokes their existing integration credentials. Webhook signing-secret rotation invalidates the previous secret immediately; coordinate receiver updates before rotation.

OpenAPI route snapshots are checked against controller mappings. A release must regenerate the merged contract, review schema/permission changes, run existing client integration tests and record any migration action in the release record. Version labels alone do not establish compatibility.
