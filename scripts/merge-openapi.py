"""Merge reviewed module fragments and installation/account extensions; validate references."""

import json
import re
from pathlib import Path

root = Path(__file__).resolve().parent.parent
target = root / "docs/api/openapi.json"
spec = json.loads(target.read_text(encoding="utf-8-sig"))
for name in (
    "business.openapi.json",
    "automation.openapi.json",
    "workforce-extensions.openapi.json",
):
    fragment = json.loads((target.parent / name).read_text(encoding="utf-8-sig"))
    spec["paths"].update(fragment["paths"])
    for category, definitions in fragment.get("components", {}).items():
        spec["components"].setdefault(category, {}).update(definitions)
spec["info"]["title"] = "EnterpriseOS live application API"
spec["info"]["version"] = "0.3.0"
spec["components"]["schemas"]["Login"].setdefault("properties", {})["otp"] = {
    "type": "string",
    "maxLength": 40,
    "description": "Required authenticator or unused recovery code when MFA is enabled.",
}
for name in ("OrganizationProfile", "UpdateOrganizationProfile"):
    spec["components"]["schemas"][name].setdefault("properties", {}).update(
        {
            "currency": {"type": "string", "default": "INR"},
            "country": {"type": "string", "default": "IN"},
            "locale": {"type": "string", "default": "en-IN"},
            "fiscalYearStartMonth": {"type": "integer", "minimum": 1, "maximum": 12},
            "gstin": {"type": "string", "maxLength": 15},
        }
    )


def expand_roles(node):
    if isinstance(node, dict):
        if isinstance(node.get("enum"), list) and {
            "ADMIN",
            "HR",
            "MANAGER",
            "MEMBER",
            "AUDITOR",
        } <= set(str(v) for v in node["enum"]):
            node["enum"] = list(
                dict.fromkeys(node["enum"] + ["SALES", "PROCUREMENT", "FINANCE", "PAYROLL"])
            )
        for value in node.values():
            expand_roles(value)
    elif isinstance(node, list):
        for value in node:
            expand_roles(value)


expand_roles(spec)


def operation(path, method, summary, properties=None, required=(), public=False, response=None):
    result = {
        "summary": summary,
        "operationId": method + "_" + re.sub(r"[^a-zA-Z0-9]+", "_", path).strip("_"),
        "tags": ["Installation and accounts"],
        "responses": {
            "200": {
                "description": "Success",
                "content": {"application/json": {"schema": response or {"type": "object"}}},
            },
            **{
                str(status): {"description": description}
                for status, description in [
                    (400, "Invalid request"),
                    (401, "Authentication required"),
                    (403, "Insufficient permission"),
                    (409, "State conflict"),
                    (429, "Rate limited"),
                ]
            },
        },
    }
    if public:
        result["security"] = []
    parameters = [
        {"name": name, "in": "path", "required": True, "schema": {"type": "string"}}
        for name in re.findall(r"\{([^}]+)\}", path)
    ]
    if method not in ("get", "head"):
        parameters.append(
            {
                "name": "Origin",
                "in": "header",
                "required": True,
                "schema": {"type": "string"},
                "description": "Exact configured same-origin frontend origin.",
            }
        )
    if parameters:
        result["parameters"] = parameters
    if properties is not None:
        result["requestBody"] = {
            "required": True,
            "content": {
                "application/json": {
                    "schema": {
                        "type": "object",
                        "properties": properties,
                        "required": list(required),
                    }
                }
            },
        }
    spec["paths"].setdefault(path, {})[method] = result


text = {"type": "string"}
password = {"type": "string", "minLength": 12, "maxLength": 72, "writeOnly": True}
operation(
    "/api/v1/setup",
    "get",
    "Whether first-run setup is available",
    public=True,
    response={"type": "object", "properties": {"required": {"type": "boolean"}}},
)
operation(
    "/api/v1/setup",
    "post",
    "Create the first company and separate platform account once",
    {
        "companyName": text,
        "ownerName": text,
        "ownerEmail": {"type": "string", "format": "email"},
        "ownerPassword": password,
        "platformEmail": {"type": "string", "format": "email"},
        "platformPassword": password,
        "timezone": text,
    },
    (
        "companyName",
        "ownerName",
        "ownerEmail",
        "ownerPassword",
        "platformEmail",
        "platformPassword",
    ),
    True,
)
operation("/api/v1/me/mfa", "get", "Read own authenticator enrollment status")
operation(
    "/api/v1/me/mfa/setup",
    "post",
    "Prepare encrypted authenticator secret after password confirmation",
    {"currentPassword": {"type": "string", "writeOnly": True}},
    ("currentPassword",),
)
operation(
    "/api/v1/me/mfa/enable",
    "post",
    "Verify authenticator and return one-time recovery codes",
    {"otp": text},
    ("otp",),
)
operation(
    "/api/v1/me/mfa/disable",
    "post",
    "Disable own authenticator using password and current OTP/recovery code",
    {"currentPassword": {"type": "string", "writeOnly": True}, "otp": text},
    ("currentPassword", "otp"),
)
rows = {
    "rows": {
        "type": "array",
        "minItems": 1,
        "maxItems": 200,
        "items": {
            "type": "object",
            "properties": {"email": {"type": "string", "format": "email"}, "role": text},
            "required": ["email", "role"],
        },
    }
}
operation(
    "/api/v1/org/user-import/preview",
    "post",
    "Validate an invitation batch without writing accounts",
    rows,
    ("rows",),
)
operation(
    "/api/v1/org/user-import", "post", "Queue validated invitations atomically", rows, ("rows",)
)
operation(
    "/api/v1/platform/runtime-metrics",
    "get",
    "Read process-scoped request counters as platform administrator",
)
operation(
    "/api/v1/org/records/{kind}/{id}/history",
    "get",
    "Read permission-filtered workforce change snapshots",
    response={"type": "array", "items": {"type": "object"}},
)
operation(
    "/api/v1/org/time-entries/{id}/submit",
    "post",
    "Submit own time entry for independent review",
    {},
)
operation(
    "/api/v1/org/time-entries/{id}/decision",
    "post",
    "Approve or reject another user time entry",
    {"decision": {"type": "string", "enum": ["APPROVED", "REJECTED"]}, "reason": text},
    ("decision", "reason"),
)
operation(
    "/api/v1/auth/register-company",
    "post",
    "Queue single-use email verification before creating a company",
    {
        "companyName": text,
        "ownerName": text,
        "email": {"type": "string", "format": "email"},
        "password": password,
        "slug": text,
        "timezone": text,
    },
    ("companyName", "ownerName", "email", "password", "slug", "timezone"),
    True,
)
operation(
    "/api/v1/auth/verify-company",
    "post",
    "Consume email proof and create a company and its owner",
    {"token": text},
    ("token",),
    True,
)
operation(
    "/api/v1/auth/refresh",
    "post",
    "Rotate the authenticated cookie, revoke its predecessor and preserve the 24-hour absolute lifetime",
)
operation("/api/v1/me/sessions", "delete", "Revoke every own session and clear the current cookie")
operation(
    "/api/v1/org/domains",
    "get",
    "List company domain ownership challenges",
    response={"type": "array", "items": {"type": "object"}},
)
operation(
    "/api/v1/org/domains",
    "post",
    "Issue a DNS TXT domain ownership challenge",
    {"domain": text},
    ("domain",),
)
operation(
    "/api/v1/org/domains/{id}/verify",
    "post",
    "Verify the exact public DNS TXT challenge for this company",
)
operation(
    "/api/v1/org/permission-catalog",
    "get",
    "List server action permissions for an administrator",
    response={"type": "array", "items": {"type": "object"}},
)
operation("/api/v1/org/my-permissions", "get", "Read the current membership action policy")
operation(
    "/api/v1/org/custom-roles",
    "get",
    "List company custom roles and their versions",
    response={"type": "array", "items": {"type": "object"}},
)
role = {
    "name": text,
    "baseRole": {
        "type": "string",
        "enum": [
            "HR",
            "MANAGER",
            "MEMBER",
            "AUDITOR",
            "SALES",
            "PROCUREMENT",
            "FINANCE",
            "PAYROLL",
        ],
    },
    "permissions": {"type": "array", "maxItems": 250, "items": text},
}
operation(
    "/api/v1/org/custom-roles",
    "post",
    "Create an action allowlist capped by a predefined non-admin role",
    role,
    ("name", "baseRole", "permissions"),
)
operation(
    "/api/v1/org/custom-roles/{id}",
    "put",
    "Update custom role and assigned membership caps with optimistic version checking",
    {**role, "version": {"type": "integer", "minimum": 1}},
    ("name", "baseRole", "permissions", "version"),
)
operation(
    "/api/v1/org/users/{id}/custom-role",
    "put",
    "Assign or remove a custom role; assignment revokes existing API credentials",
    {"customRoleId": text},
    ("customRoleId",),
)
operation(
    "/api/v1/platform/login-attempts",
    "get",
    "Platform-only paginated authentication outcomes with hashed email and IP identifiers",
    response={"type": "array", "items": {"type": "object"}},
)


def walk(node):
    if isinstance(node, dict):
        if "$ref" in node and node["$ref"].startswith("#/"):
            current = spec
            for part in node["$ref"][2:].split("/"):
                current = current[part]
        for value in node.values():
            walk(value)
    elif isinstance(node, list):
        for value in node:
            walk(value)


walk(spec)
for path, entry in spec["paths"].items():
    for method, op in entry.items():
        if method not in ("get", "put", "post", "delete", "patch", "head", "options"):
            continue
        names = {p["name"] for p in op.get("parameters", []) if p.get("in") == "path"}
        names.update(p["name"] for p in entry.get("parameters", []) if p.get("in") == "path")
        assert set(re.findall(r"\{([^}]+)\}", path)) <= names, (path, method)
target.write_text(json.dumps(spec, indent=2) + "\n", encoding="utf-8")
print(
    f'Validated {len(spec["paths"])} paths; all local schema references and path parameters resolved.'
)
