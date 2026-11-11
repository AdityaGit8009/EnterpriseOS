package com.enterpriseos;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me/mfa")
public class MfaController {
    private final MfaService mfa;
    private final JdbcTemplate db;
    private final BCryptPasswordEncoder passwords;
    private final AuditService audit;

    public MfaController(MfaService mfa, JdbcTemplate db, BCryptPasswordEncoder passwords, AuditService audit) {
        this.mfa = mfa;
        this.db = db;
        this.passwords = passwords;
        this.audit = audit;
    }

    @GetMapping
    public Map<String, Object> status(@RequestAttribute Principal principal) {
        return mfa.status(principal.userId());
    }

    @PostMapping("/setup")
    @Transactional
    public Map<String, Object> setup(@RequestAttribute Principal principal, @RequestBody Map<String, Object> body) {
        password(principal, body);
        String secret = mfa.prepare(principal.userId());
        return Map.of("secret", secret, "otpauthUri",
                "otpauth://totp/" + URLEncoder.encode("EnterpriseOS:" + principal.email(), StandardCharsets.UTF_8)
                        + "?secret=" + secret + "&issuer=EnterpriseOS&algorithm=SHA1&digits=6&period=30");
    }

    @PostMapping("/enable")
    @Transactional
    public Map<String, Object> enable(@RequestAttribute Principal principal, @RequestBody Map<String, Object> body) {
        var codes = mfa.enable(principal.userId(), Inputs.text(body, "otp", 40));
        audit.log(principal, "MFA_ENABLED", "user", principal.userId());
        return Map.of("recoveryCodes", codes, "message",
                "Save these recovery codes privately. They are shown only once.");
    }

    @PostMapping("/disable")
    @Transactional
    public Map<String, Object> disable(@RequestAttribute Principal principal, @RequestBody Map<String, Object> body) {
        password(principal, body);
        mfa.disable(principal.userId(), Inputs.text(body, "otp", 40));
        audit.log(principal, "MFA_DISABLED", "user", principal.userId());
        return Map.of("message", "Authenticator disabled.");
    }

    private void password(Principal p, Map<String, Object> body) {
        String value = Inputs.text(body, "currentPassword", 256);
        String hash = db.queryForObject("SELECT password_hash FROM users WHERE id=? FOR UPDATE", String.class,
                p.userId());
        if (!passwords.matches(value, hash))
            throw new ApiException(403, "Current password is incorrect.");
    }
}
