package com.enterpriseos;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
@Service public class PasswordPolicyService {
    private final JdbcTemplate db;private final BCryptPasswordEncoder encoder;
    public PasswordPolicyService(JdbcTemplate db,BCryptPasswordEncoder encoder){this.db=db;this.encoder=encoder;}
    public void enforceAndRemember(String user,String password,String currentHash){
        if(encoder.matches(password,currentHash))throw new ApiException(400,"Choose a password different from your current and previous five passwords.");
        for(String previous:db.queryForList("SELECT password_hash FROM password_history WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 5",String.class,user))if(encoder.matches(password,previous))throw new ApiException(400,"Choose a password different from your current and previous five passwords.");
        db.update("INSERT INTO password_history(id,user_id,password_hash) VALUES (?,?,?)",UUID.randomUUID().toString(),user,currentHash);
    }
}
