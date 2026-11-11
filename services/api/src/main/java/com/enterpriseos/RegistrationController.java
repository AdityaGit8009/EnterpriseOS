package com.enterpriseos;

import java.util.*;
import javax.naming.directory.InitialDirContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class RegistrationController {
    private final JdbcTemplate db;private final BCryptPasswordEncoder passwords;private final MailService mail;private final AuditService audit;private final String baseUrl;private final DnsTxtResolver dns;
    public RegistrationController(JdbcTemplate db,BCryptPasswordEncoder passwords,MailService mail,AuditService audit,DnsTxtResolver dns,@Value("${app.public-base-url}")String baseUrl){this.db=db;this.passwords=passwords;this.mail=mail;this.audit=audit;this.dns=dns;this.baseUrl=baseUrl;}
    @PostMapping("/auth/register-company") @Transactional public Map<String,Object> register(@RequestBody Map<String,Object> body){
        if(db.queryForObject("SELECT COUNT(*) FROM users WHERE platform_role='PLATFORM_ADMIN' AND status='ACTIVE'",Long.class)==0)throw new ApiException(409,"The platform owner must complete installation setup before public registration opens.");
        String company=Inputs.text(body,"companyName",180),name=Inputs.text(body,"ownerName",180),email=Inputs.email(body,"email"),password=Inputs.password(body),timezone=Inputs.timezone(body),slug=Inputs.text(body,"slug",80).toLowerCase(Locale.ROOT);
        if(!slug.matches("[a-z0-9][a-z0-9-]{1,78}[a-z0-9]"))throw new ApiException(400,"Workspace slug must be 3 to 80 lowercase letters, digits or internal hyphens.");
        Map<String,Object> response=Map.of("message","If registration is eligible, an email verification link has been queued. Verify it before signing in.");
        if(db.queryForObject("SELECT COUNT(*) FROM users WHERE email=?",Long.class,email)>0)return response;
        if(db.queryForObject("SELECT COUNT(*) FROM tenants WHERE slug=?",Long.class,slug)>0)throw new ApiException(409,"Choose another workspace slug.");
        String token=Tokens.issue();
        db.update("INSERT INTO company_registrations(id,email,company_name,owner_name,slug,timezone,password_hash,token_hash,expires_at) VALUES (?,?,?,?,?,?,?,?,TIMESTAMPADD(HOUR,24,UTC_TIMESTAMP(6))) ON DUPLICATE KEY UPDATE company_name=VALUES(company_name),owner_name=VALUES(owner_name),slug=VALUES(slug),timezone=VALUES(timezone),password_hash=VALUES(password_hash),token_hash=VALUES(token_hash),expires_at=VALUES(expires_at),verified_at=NULL",UUID.randomUUID().toString(),email,company,name,slug,timezone,passwords.encode(password),Tokens.hash(token));
        mail.queue(email,"Verify your EnterpriseOS company registration","Verify your email to create your company workspace. This single-use link expires in 24 hours:\n\n"+baseUrl+"/?flow=verify-company&token="+token+"\n\nIf you did not request this, ignore the message.");return response;
    }
    @PostMapping("/auth/verify-company") @Transactional public Map<String,Object> verify(@RequestBody Map<String,Object> body){
        String token=Inputs.text(body,"token",256);var rows=db.queryForList("SELECT * FROM company_registrations WHERE token_hash=? AND verified_at IS NULL AND expires_at>UTC_TIMESTAMP(6) FOR UPDATE",Tokens.hash(token));
        if(rows.isEmpty())throw new ApiException(400,"Verification link is expired, invalid or already used.");var row=rows.getFirst();
        if(db.queryForObject("SELECT COUNT(*) FROM users WHERE email=?",Long.class,row.get("email"))>0)throw new ApiException(409,"An account already exists for this email. Sign in or use password reset.");
        String tenant=UUID.randomUUID().toString(),user=UUID.randomUUID().toString();
        db.update("INSERT INTO tenants(id,name,slug,status,timezone,created_at) VALUES (?,?,?,'ACTIVE',?,UTC_TIMESTAMP(6))",tenant,row.get("company_name"),row.get("slug"),row.get("timezone"));
        db.update("INSERT INTO organization_profiles(tenant_id,legal_name,email) VALUES (?,?,?)",tenant,row.get("company_name"),row.get("email"));
        db.update("INSERT INTO users(id,name,email,password_hash,status,created_at) VALUES (?,?,?,?,'ACTIVE',UTC_TIMESTAMP(6))",user,row.get("owner_name"),row.get("email"),row.get("password_hash"));
        db.update("INSERT INTO memberships(id,tenant_id,user_id,role,status) VALUES (?,?,?,'ADMIN','ACTIVE')",UUID.randomUUID().toString(),tenant,user);
        db.update("UPDATE company_registrations SET verified_at=UTC_TIMESTAMP(6) WHERE id=?",row.get("id"));
        audit.log(new Principal(user,tenant,row.get("owner_name").toString(),row.get("email").toString(),"ADMIN"),"COMPANY_EMAIL_VERIFIED","tenant",tenant);
        return Map.of("message","Email verified and company created. Sign in with your registration credentials.");
    }
    @GetMapping("/org/domains") public List<Map<String,Object>> domains(@RequestAttribute Principal principal){Inputs.requireRole(principal,"ADMIN");return db.queryForList("SELECT id,domain,status,CONCAT('_enterpriseos.',domain) AS txtName,challenge AS txtValue,expires_at AS expiresAt,verified_at AS verifiedAt FROM tenant_domains WHERE tenant_id=? ORDER BY domain",principal.tenantId());}
    @PostMapping("/org/domains") @Transactional public Map<String,Object> claim(@RequestAttribute Principal principal,@RequestBody Map<String,Object> body){
        lockAdministrator(principal);String domain=Inputs.text(body,"domain",253).toLowerCase(Locale.ROOT);
        if(!domain.matches("(?=.{4,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")||Set.of("gmail.com","outlook.com","yahoo.com","hotmail.com").contains(domain))throw new ApiException(400,"Enter a business domain that your organization controls, without a URL or path.");
        db.queryForList("SELECT id FROM tenants WHERE id=? FOR UPDATE",principal.tenantId());var existing=db.queryForList("SELECT * FROM tenant_domains WHERE domain=? FOR UPDATE",domain);
        if(!existing.isEmpty()&&!principal.tenantId().equals(existing.getFirst().get("tenant_id")))throw new ApiException(409,"This domain has already been claimed. Contact the platform administrator to review ownership.");
        if(!existing.isEmpty()&&"VERIFIED".equals(existing.getFirst().get("status")))throw new ApiException(409,"This domain is already verified.");
        String id=existing.isEmpty()?UUID.randomUUID().toString():existing.getFirst().get("id").toString(),challenge="eos-verify="+Tokens.issue();
        db.update("INSERT INTO tenant_domains(id,tenant_id,domain,challenge,expires_at) VALUES (?,?,?,?,TIMESTAMPADD(HOUR,24,UTC_TIMESTAMP(6))) ON DUPLICATE KEY UPDATE challenge=VALUES(challenge),expires_at=VALUES(expires_at),status='PENDING'",id,principal.tenantId(),domain,challenge);
        audit.log(principal,"DOMAIN_CLAIM_CREATED","domain",id);return Map.of("id",id,"domain",domain,"txtName","_enterpriseos."+domain,"txtValue",challenge,"status","PENDING");
    }
    @PostMapping("/org/domains/{id}/verify") @Transactional public Map<String,Object> verifyDomain(@RequestAttribute Principal principal,@PathVariable String id){
        lockAdministrator(principal);var rows=db.queryForList("SELECT * FROM tenant_domains WHERE tenant_id=? AND id=? AND expires_at>UTC_TIMESTAMP(6) FOR UPDATE",principal.tenantId(),id);if(rows.isEmpty())throw new ApiException(404,"Active domain challenge not found. Create a new challenge if it expired.");var row=rows.getFirst();
        boolean matched=dns.lookup("_enterpriseos."+row.get("domain")).contains(row.get("challenge").toString());
        if(!matched)throw new ApiException(409,"DNS TXT value does not match this active challenge.");db.update("UPDATE tenant_domains SET status='VERIFIED',verified_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND id=?",principal.tenantId(),id);audit.log(principal,"DOMAIN_VERIFIED","domain",id);return Map.of("id",id,"domain",row.get("domain"),"status","VERIFIED");
    }
    private void lockAdministrator(Principal p){Inputs.requireRole(p,"ADMIN");db.queryForList("SELECT id FROM tenants WHERE id=? FOR UPDATE",p.tenantId());if(db.queryForObject("SELECT COUNT(*) FROM memberships m JOIN tenants t ON t.id=m.tenant_id JOIN users u ON u.id=m.user_id WHERE m.tenant_id=? AND m.user_id=? AND m.role='ADMIN' AND m.status='ACTIVE' AND t.status='ACTIVE' AND u.status='ACTIVE'",Long.class,p.tenantId(),p.userId())!=1)throw new ApiException(403,"Administrator authority changed. Sign in again.");}
}
