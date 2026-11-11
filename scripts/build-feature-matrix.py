import csv
from pathlib import Path
root=Path(__file__).resolve().parent.parent
backlog=list(csv.DictReader((root/'EnterpriseOS_Feature_Backlog.csv').open(encoding='utf-8-sig')))
coverage={}
for file in ('phase3-coverage.csv','phase4-5-coverage.csv','phase2-extension-coverage.csv'):
    for row in csv.DictReader((root/'docs'/file).open(encoding='utf-8-sig')):
        coverage[row['Feature ID']]=row

# Conservative coverage of the original foundation/workforce implementation.
# A stored arbitrary JSON property alone is not counted as a complete feature.
implemented={
'101':{3,4,5,7,8,9,12,14},'102':{1,2,5,11,12,14},'103':{1,3,5,8,9,12,14},'104':{1,2,3,4,5,6,7,8,9,11,14},
'205':{2,3,4,5,6,8,9,10,12,15},'206':{1,2,3,4,5,7,9,11,12,14,15},'207':{1,2,3,4,5,6,13},'208':{1,2,3,4,5,6,7,8,12,13,16,17}}
partial={
'101':{6,10,11,13},'102':{3,4,6,7,8,9,13},'103':{2,4,7,10,11,13},'104':{12,13},
'205':{1,7,13,14},'206':{6,8,10,13},'207':{7,8,9,12,14},'208':{9,10,11}}
notes={
'F-101-01':'No public self-registration. First-run company setup and platform-admin tenant provisioning are implemented.',
'F-101-02':'No email-domain ownership verification workflow.',
'F-101-10':'Legal/display name, address and GSTIN; no comprehensive multi-entity legal registry.',
'F-102-04':'Length/UTF-8 limits and BCrypt; historical-password reuse policy is not implemented.',
'F-102-06':'Own session listing/revocation; no device fingerprint inventory.',
'F-102-07':'Individual session revocation; password change/reset revokes all. No standalone revoke-all control.',
'F-102-09':'Successful login audit exists; failed-attempt audit event persistence is not complete.',
'F-102-10':'Opaque expiring cookie sessions are used; no OAuth-style refresh-token rotation protocol.',
'F-102-13':'Expiring single-use invitation with password establishment/current-password verification; not passwordless authentication.',
'F-103-06':'Custom action-level role builder is not implemented; nine predefined business roles are enforced.',
'F-103-10':'Tenant/object ownership checks exist; comprehensive branch/department ACL policy is not implemented.',
'F-103-13':'Password reauthentication protects MFA configuration; not every privileged business action.',
'F-104-10':'Reporting hierarchy records exist; no graphical organization chart.',
'F-104-12':'Events use UTC and company timezone is saved; all displays do not yet consistently use company timezone.',
'F-104-13':'Commercial document numbering and employee auto-IDs; no general user-configurable sequence designer.',
'F-205-01':'Validated core fields plus HR-only extension payload; no custom-field schema designer.',
'F-205-07':'Employment type, joining/contract end dates and controlled document attachments; no contract generation/signing.',
'F-205-11':'No employee resignation-request approval workflow.',
'F-205-13':'HR can reactivate employment status; membership reactivation is a separate controlled action.',
'F-205-14':'Document retention and versioning exist; employee document-expiry reminder scheduling is not complete.',
'F-206-06':'Informational late/overtime summary; no fully configurable payable overtime policy.',
'F-206-08':'Manual source/timestamp tracking; no complete remote/onsite attendance policy.',
'F-206-10':'Attendance list and date-filtered report exports; no dedicated complete monthly policy reconciliation screen.',
'F-206-13':'General notifications/SLA jobs exist; missing-punch alert job is not implemented.',
'F-207-07':'Policy holiday dates; full location-specific calendar management is incomplete.',
'F-207-08':'Fractional requested days are supported within policy; no precise hourly interval scheduling model.',
'F-207-09':'Controlled document center exists; leave-request attachment linkage is incomplete.',
'F-207-10':'No leave encashment entitlement/payroll settlement engine.',
'F-207-11':'Manual balance adjustment exists; no distinct earned compensatory-off policy workflow.',
'F-207-12':'Team leave list/history and approvals; no visual team leave calendar.',
'F-207-14':'Insufficient leave is rejected; a complete configurable negative-balance policy is not implemented.',
'F-208-09':'Validated statuses and archived-project rules; no configurable per-project transition designer.',
'F-208-10':'Task comments are persistent; rich mentions/reactions are incomplete.',
'F-208-11':'Project-scoped controlled documents; task-specific attachment/link UX is incomplete.',
'F-208-14':'Recurring task scheduling is not implemented.',
'F-208-15':'No graphical Gantt view.',
'F-208-18':'No dedicated project risk register workflow.'}

# Explicitly reviewed extensions supersede the earlier baseline descriptions.
extensions={
'F-101-01':('implemented','RegistrationController.java','Pending registration, hashed single-use email proof and transactional company/owner creation; real SMTP must be configured for delivery.'),
'F-101-02':('implemented','RegistrationController.java; DnsTxtResolver.java','Company-scoped expiring DNS TXT proof; exact ownership match and cross-tenant checks. Requires control of an actual business domain.'),
'F-102-03':('implemented','RegistrationController.java; AuthIntegrationTest.java','Company self-registration and invitation activation require single-use email proof. First installed owner is established through controlled first-run setup.'),
'F-102-04':('implemented','PasswordPolicyService.java; AuthIntegrationTest.java','BCrypt, length/UTF-8 bounds, current and five previous password reuse rejection on change/reset.'),
'F-102-06':('implemented','AuthController.java','Own active session listing includes creation/expiry and supplied user-agent description; user agent is informational, not hardware attestation.'),
'F-102-07':('implemented','AuthController.java','Individual and revoke-all controls invalidate sessions server-side and clear current cookies.'),
'F-102-09':('implemented','AuthenticationEvents.java; AuthenticationAuditController.java','Success/failure/lockout/MFA outcomes persist independently of failed-login rollback; platform-only pagination with hashed identifiers.'),
'F-102-10':('implemented','AuthController.java; AuthIntegrationTest.java','Opaque cookie rotation revokes predecessor and enforces 24-hour absolute lifetime. This is session renewal, not an OAuth refresh-token grant.'),
'F-103-06':('implemented','CustomAccessController.java; CustomAccessFilter.java','Versioned action allowlists bounded by predefined non-admin roles; assigned memberships update immediately and existing API credentials are revoked.'),
'F-103-07':('implemented','CustomAccessController.java; CustomAccessPolicy.java','Server method/route action catalog with base-role and existing tenant/object restrictions. Internal report and notification paths apply current policies.'),
'F-104-10':('implemented','apps/web/src/PlanningViews.tsx; tests/planning-and-forms.test.cjs','Organization chart uses actual employee/manager/department relationships and identifies cyclic imported hierarchies for review.'),
'F-208-15':('implemented','apps/web/src/PlanningViews.tsx; tests/planning-and-forms.test.cjs','Timeline/Gantt uses recorded task/sprint/milestone dates and labels inherited ranges; no dates are invented for undated work.'),
'F-418-12':('implemented','docs/API_LIFECYCLE.md','Versioning/deprecation compatibility and credential rotation policy documented. No deprecated endpoint or fabricated Sunset date is advertised.'),
'F-521-12':('implemented','scripts/load-smoke.cjs; tmp/load-evidence.json','Measured 1,200 synthetic authenticated read requests at concurrency 1/8/24/64 against isolated MySQL with zero failures. Includes latency percentiles; does not establish production capacity or contractual SLA.')
}
for key,(status,evidence,detail) in extensions.items():
    coverage[key]={'Status':status,'Evidence':evidence,'Limitations':detail}

result=[]
for feature in backlog:
    key=feature['Feature ID'];phase=feature['Phase']
    if key in coverage:
        row=coverage[key];status=row.get('Status',row.get('status','partial')).lower().replace('_',' ')
        evidence=row.get('Evidence',row.get('Implementation evidence',''))
        detail=row.get('Limitations or behavior',row.get('Limitations or remaining work',row.get('Notes',row.get('Limitations',''))))
    else:
        _,group,number=key.split('-');number=int(number)
        status='implemented' if number in implemented.get(group,set()) else 'partial' if number in partial.get(group,set()) else 'not implemented'
        evidence='services/api/src/main/java/com/enterpriseos/; apps/web/src/App.tsx; services/api/src/test/java/com/enterpriseos/' if status!='not implemented' else ''
        detail=notes.get(key,'See implementation, API contract and verification evidence; client policy acceptance remains separate.' if status=='implemented' else 'Partially covered by the current module; do not treat as complete without feature-specific acceptance.')
    result.append({'Feature ID':key,'Phase':phase,'Module':feature['Module'],'Feature':feature['Feature'],'Status':status,'Evidence':evidence,'Limitations':detail})
assert len(result)==len(backlog)==316
output=root/'docs/FEATURE_IMPLEMENTATION_MATRIX.csv'
with output.open('w',newline='',encoding='utf-8') as file:
    writer=csv.DictWriter(file,fieldnames=list(result[0]));writer.writeheader();writer.writerows(result)
from collections import Counter
print('Reviewed all 316 feature IDs:',dict(Counter(r['Status'] for r in result)))
