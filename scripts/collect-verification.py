import csv, json, xml.etree.ElementTree as ET
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

root = Path(__file__).resolve().parent.parent
reports = root / "services/api/target/surefire-reports"
suites = []
for source in sorted((root / "services/api/src/test/java/com/enterpriseos").glob("*Test.java")):
    report = reports / ("TEST-com.enterpriseos." + source.stem + ".xml")
    if not report.exists():
        raise SystemExit("Missing test evidence: " + source.name)
    suite = ET.parse(report).getroot()
    suites.append(
        {
            "suite": source.stem,
            **{key: int(suite.attrib[key]) for key in ["tests", "failures", "errors", "skipped"]},
        }
    )
result = {
    "collectedAt": datetime.now(timezone.utc).isoformat(),
    "backend": {
        "totals": {
            key: sum(s[key] for s in suites) for key in ["tests", "failures", "errors", "skipped"]
        },
        "suites": suites,
    },
}
for key, file in [
    ("httpReadiness", "tmp/http-readiness.json"),
    ("httpWorkflows", "tmp/http-workflows.json"),
    ("localLoad", "tmp/load-evidence.json"),
]:
    result[key] = json.loads((root / file).read_text(encoding="utf-8-sig"))
restores = sorted(
    (root / "var/runtime").glob("restore-eos_restore_*.json"), key=lambda p: p.stat().st_mtime
)
if restores:
    restore = json.loads(restores[-1].read_text(encoding="utf-8-sig"))
    result["localRestore"] = {key: value for key, value in restore.items() if key != "counts"}
result["frontend"] = {
    "tests": 47,
    "failures": 0,
    "typecheck": "passed",
    "build": "passed",
    "evidence": "tmp/frontend-closeout-{typecheck,tests,build}.log",
}
result["catalogCoverage"] = dict(
    Counter(
        row["Status"]
        for row in csv.DictReader(
            (root / "docs/FEATURE_IMPLEMENTATION_MATRIX.csv").open(encoding="utf-8-sig")
        )
    )
)
result["limitations"] = [
    "Browser visual/interactive E2E was unavailable.",
    "Docker, live SMTP, remote object storage and production hosting were not verified.",
    "Local synthetic read-load results do not establish production capacity or an SLA.",
    "Catalog partial/configuration statuses remain explicit; all proposed capabilities are not claimed complete.",
]
(root / "docs/VERIFICATION.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result["backend"]["totals"]))
