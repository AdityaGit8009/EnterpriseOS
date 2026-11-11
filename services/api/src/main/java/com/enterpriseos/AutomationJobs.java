package com.enterpriseos;

import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AutomationJobs {
    private final AutomationSupport s;
    private final ReportService reports;
    private final WorkflowFormService forms;

    public AutomationJobs(AutomationSupport s, ReportService reports, WorkflowFormService forms) {
        this.s = s;
        this.reports = reports;
        this.forms = forms;
    }

    @Scheduled(fixedDelayString = "${APP_WORKFLOW_POLL_MS:60000}")
    @Transactional
    public void escalate() {
        var due = s.db.queryForList(
                "SELECT id,tenant_id FROM workflow_instances WHERE state='PENDING' AND escalated=FALSE AND due_at<UTC_TIMESTAMP(6) ORDER BY tenant_id,id LIMIT 20");
        for (var key : due) {
            String tenant = key.get("tenant_id").toString(), id = key.get("id").toString();
            String status = s.db.queryForObject("SELECT status FROM tenants WHERE id=? FOR UPDATE", String.class,
                    tenant);
            if (!"ACTIVE".equals(status))
                continue;
            var rows = s.db.queryForList(
                    "SELECT * FROM workflow_instances WHERE id=? AND tenant_id=? AND state='PENDING' AND escalated=FALSE AND due_at<UTC_TIMESTAMP(6) FOR UPDATE",
                    id, tenant);
            if (rows.isEmpty())
                continue;
            var instance = rows.getFirst();
            var steps = s.mapList(s.decode(instance.get("assignment_snapshot")).get("steps"));
            int index = ((Number) instance.get("current_step")).intValue();
            var step = steps.get(index);
            List<String> admins = s.db.queryForList(
                    "SELECT m.user_id FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.tenant_id=? AND m.role='ADMIN' AND m.status='ACTIVE' AND u.status='ACTIVE' AND m.user_id<>?",
                    String.class, tenant, instance.get("requester_id"));
            if (admins.isEmpty()) {
                s.events.notify(tenant, instance.get("requester_id").toString(), id + ":escalation-unavailable",
                        "WORKFLOW_ESCALATION", "Approval overdue",
                        "No independent active administrator is available for escalation; the request remains pending.");
                s.db.update("UPDATE workflow_instances SET escalated=TRUE WHERE id=?", id);
                continue;
            }
            step.put("approverIds", admins);
            s.db.update(
                    "UPDATE workflow_instances SET assignment_snapshot=?,escalated=TRUE,row_version=row_version+1,due_at=TIMESTAMPADD(HOUR,24,UTC_TIMESTAMP(6)) WHERE id=?",
                    s.encode(forms.replaceSteps(instance.get("assignment_snapshot"), steps)), id);
            s.db.update(
                    "INSERT INTO workflow_actions(id,tenant_id,instance_id,step_number,actor_id,action,reason) VALUES (?,?,?,?,?,'ESCALATED','SLA expired; reassigned to independent active organization administrators')",
                    s.id(), tenant, id, index, "00000000-0000-0000-0000-000000000000");
            for (String user : admins)
                s.events.notify(tenant, user, id + ":escalated:" + user, "WORKFLOW_ESCALATION",
                        "Overdue approval escalated", instance.get("title").toString());
        }
    }

    @Scheduled(fixedDelayString = "${APP_REPORT_POLL_MS:60000}")
    public void scheduledReports() {
        var due = s.db.queryForList(
                "SELECT * FROM report_definitions WHERE cadence_hours>0 AND next_run_at<=UTC_TIMESTAMP(6) ORDER BY next_run_at LIMIT 5");
        for (var job : due) {
            String id = job.get("id").toString();
            if (s.db.update(
                    "UPDATE report_definitions SET next_run_at=TIMESTAMPADD(HOUR,cadence_hours,UTC_TIMESTAMP(6)) WHERE id=? AND cadence_hours>0 AND next_run_at<=UTC_TIMESTAMP(6)",
                    id) == 0)
                continue;
            try {
                var users = s.db.queryForList(
                        "SELECT u.id,u.name,u.email,m.role FROM memberships m JOIN users u ON u.id=m.user_id JOIN tenants t ON t.id=m.tenant_id WHERE m.tenant_id=? AND m.user_id=? AND m.status='ACTIVE' AND u.status='ACTIVE' AND t.status='ACTIVE'",
                        job.get("tenant_id"), job.get("owner_id"));
                if (users.isEmpty())
                    throw new ApiException(403, "Report owner no longer has active organization access.");
                var user = users.getFirst();
                Principal p = new Principal(user.get("id").toString(), job.get("tenant_id").toString(),
                        user.get("name").toString(), user.get("email").toString(), user.get("role").toString());
                reports.generate(p, job.get("name").toString(), job.get("kind").toString(),
                        s.decode(job.get("filters")), id);
            } catch (Exception ex) {
                String error = ex instanceof ApiException ? ex.getMessage()
                        : "Report generation failed; inspect server diagnostics.";
                s.db.update(
                        "INSERT INTO report_exports(id,tenant_id,owner_id,definition_id,name,kind,content,row_manifest,row_count,status,error_message,expires_at) VALUES (?,?,?,?,?,?,'','{}',0,'FAILED',?,TIMESTAMPADD(DAY,7,UTC_TIMESTAMP(6)))",
                        s.id(), job.get("tenant_id"), job.get("owner_id"), id, job.get("name"), job.get("kind"),
                        error.substring(0, Math.min(500, error.length())));
            }
        }
        s.db.update(
                "UPDATE report_exports SET content='',status='EXPIRED' WHERE expires_at<=UTC_TIMESTAMP(6) AND status<>'EXPIRED'");
    }

    @Scheduled(fixedDelayString = "${APP_SUBSCRIPTION_POLL_MS:3600000}")
    @Transactional
    public void renewalWarnings() {
        for (var sub : s.db.queryForList(
                "SELECT id,tenant_id,expires_at FROM tenant_subscriptions WHERE status IN ('ACTIVE','TRIAL') AND expires_at BETWEEN UTC_TIMESTAMP(6) AND TIMESTAMPADD(DAY,7,UTC_TIMESTAMP(6))")) {
            String tenant = sub.get("tenant_id").toString();
            for (String admin : s.db.queryForList(
                    "SELECT user_id FROM memberships WHERE tenant_id=? AND role='ADMIN' AND status='ACTIVE'",
                    String.class, tenant))
                s.events.notify(tenant, admin, sub.get("id") + ":renewal:" + sub.get("expires_at"),
                        "SUBSCRIPTION_RENEWAL", "Subscription renewal approaching", "Your subscription expires at "
                                + sub.get("expires_at") + " UTC. Contact the platform billing administrator.");
        }
    }
}
