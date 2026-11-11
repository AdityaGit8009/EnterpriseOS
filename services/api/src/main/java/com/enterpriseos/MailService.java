package com.enterpriseos;

import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MailService {
    private final JdbcTemplate jdbc;
    private final String smtpHost, from;
    private final Path outbox;
    private final JavaMailSenderImpl sender;
    public MailService(JdbcTemplate jdbc, @Value("${spring.mail.host:}") String host,
            @Value("${spring.mail.port:587}") int port, @Value("${spring.mail.username:}") String username,
            @Value("${spring.mail.password:}") String password,
            @Value("${spring.mail.properties.mail.smtp.auth:true}") boolean auth,
            @Value("${spring.mail.properties.mail.smtp.starttls.enable:true}") boolean tls,
            @Value("${app.mail-from}") String from, @Value("${app.mail-outbox-dir}") String outbox) {
        this.jdbc = jdbc; this.smtpHost = host; this.from = from; this.outbox = Path.of(outbox).toAbsolutePath().normalize();
        sender = new JavaMailSenderImpl(); sender.setHost(host); sender.setPort(port); sender.setUsername(username); sender.setPassword(password);
        var props = sender.getJavaMailProperties(); props.setProperty("mail.smtp.auth", Boolean.toString(auth)); props.setProperty("mail.smtp.starttls.enable", Boolean.toString(tls));
        props.setProperty("mail.smtp.connectiontimeout", "5000"); props.setProperty("mail.smtp.timeout", "5000"); props.setProperty("mail.smtp.writetimeout", "5000");
    }
    public void queue(String email, String subject, String body) {
        jdbc.update("INSERT INTO mail_queue(id,recipient,subject,body,status,attempts,next_attempt_at,created_at) VALUES (?,?,?,?,'PENDING',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", UUID.randomUUID().toString(), email, subject, body);
    }
    @Scheduled(fixedDelayString = "${APP_MAIL_POLL_MS:10000}")
    @Transactional
    public void deliver() {
        var jobs = jdbc.queryForList("SELECT * FROM mail_queue WHERE status='PENDING' AND next_attempt_at<=UTC_TIMESTAMP(6) ORDER BY created_at LIMIT 10 FOR UPDATE SKIP LOCKED");
        for (var job : jobs) {
            String id = job.get("id").toString(); int attempts = ((Number)job.get("attempts")).intValue();
            try {
                String recipient = job.get("recipient").toString(), subject = job.get("subject").toString(), body = job.get("body").toString();
                if (!smtpHost.isBlank()) {
                    var message = new SimpleMailMessage(); message.setFrom(from); message.setTo(recipient); message.setSubject(subject); message.setText(body); sender.send(message);
                } else {
                    Files.createDirectories(outbox);
                    Path path = outbox.resolve(id + ".eml");
                    Files.writeString(path, "From: " + from + "\nTo: " + recipient + "\nSubject: " + subject + "\n\n" + body, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    try { Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
                    catch (UnsupportedOperationException ignored) { /* Windows inherits the configured private directory ACL. */ }
                }
                jdbc.update("UPDATE mail_queue SET status='SENT',sent_at=UTC_TIMESTAMP(6),body='',attempts=attempts+1 WHERE id=?", id);
            } catch (Exception ex) {
                jdbc.update("UPDATE mail_queue SET attempts=attempts+1,status=?,next_attempt_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)) WHERE id=?", attempts >= 4 ? "FAILED" : "PENDING", Math.min(3600, 30 * (1 << attempts)), id);
            }
        }
    }
}
