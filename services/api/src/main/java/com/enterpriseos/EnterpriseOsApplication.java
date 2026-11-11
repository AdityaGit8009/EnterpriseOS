package com.enterpriseos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootApplication
@EnableScheduling
public class EnterpriseOsApplication {
    public static void main(String[] args) {
        String pidFile=System.getenv("APP_PID_FILE");
        if(pidFile!=null&&!pidFile.isBlank())try{java.nio.file.Files.writeString(java.nio.file.Path.of(pidFile),Long.toString(ProcessHandle.current().pid()));}catch(java.io.IOException ex){throw new IllegalStateException("Cannot record local runtime process identity",ex);}
        SpringApplication.run(EnterpriseOsApplication.class, args);
    }
    @Bean BCryptPasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
}
