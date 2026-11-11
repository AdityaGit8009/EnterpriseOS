package com.enterpriseos;

import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/org/notifications")
public class NotificationExtensionsController {
    private final AutomationSupport s;private final MentionService mentions;private final NotificationDigestService digests;private final NotificationVisibility visibility;
    public NotificationExtensionsController(AutomationSupport s,MentionService mentions,NotificationDigestService digests,NotificationVisibility visibility){this.s=s;this.mentions=mentions;this.digests=digests;this.visibility=visibility;}
    @PostMapping("/mentions") @Transactional public Map<String,Object> mention(@RequestAttribute Principal principal,@RequestBody Map<String,Object> body){s.allow(principal,"ADMIN","HR","MANAGER","MEMBER","SALES","PROCUREMENT","FINANCE","PAYROLL");s.lock(principal,"WORKFLOWS",false);var result=mentions.create(principal,Inputs.text(body,"body",2000),body.get("recipientIds"),Inputs.optional(body,"projectId",36,""),s.id());if(result.get("id")!=null)s.audit.log(principal,"MENTION_CREATED","mention",result.get("id").toString());return result;}
    @GetMapping("/mentions") public List<Map<String,Object>> mentions(@RequestAttribute Principal principal){s.org(principal);return s.db.queryForList("SELECT id,author_id AS authorId,project_id AS projectId,body,created_at AS createdAt FROM notification_mentions WHERE tenant_id=? AND (author_id=? OR JSON_CONTAINS(recipient_ids,JSON_QUOTE(?))) ORDER BY created_at DESC LIMIT 200",principal.tenantId(),principal.userId(),principal.userId()).stream().filter(row->row.get("projectId")==null||visibility.project(principal,row.get("projectId").toString())).toList();}
    @GetMapping("/digest") public Map<String,Object> digest(@RequestAttribute Principal principal){return digests.settings(principal);}
    @PutMapping("/digest") @Transactional public Map<String,Object> digest(@RequestAttribute Principal principal,@RequestBody Map<String,Object> body){return digests.configure(principal,body);}
    @PostMapping("/digest/send") @Transactional public Map<String,Object> send(@RequestAttribute Principal principal){return digests.send(principal);}
    @GetMapping("/digest/history") public List<Map<String,Object>> history(@RequestAttribute Principal principal){s.org(principal);return s.db.queryForList("SELECT d.id,d.notification_count AS notificationCount,d.status,m.status AS mailStatus,d.created_at AS createdAt FROM notification_digest_runs d LEFT JOIN mail_queue m ON m.id=d.mail_id WHERE d.tenant_id=? AND d.user_id=? ORDER BY d.created_at DESC LIMIT 100",principal.tenantId(),principal.userId());}
}
