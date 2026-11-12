package com.mymicroservice.notification.mail;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.mail.internet.MimeMessage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;


@Component
public class EmailDispatcher {
    private static final Map<String, String> SUBJECTS = Map.of(
            "order-created", "訂單已成立",
            "order-paid", "付款成功",
            "order-shipped", "訂單已出貨",
            "order-cancelled", "訂單已取消",
            "payment-failed", "付款失敗");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JavaMailSender mailSender;
    private final String from;
    private final int batchSize;

    public EmailDispatcher(JdbcTemplate jdbc, TransactionTemplate transactions, JavaMailSender mailSender,
            @Value("${notification.mail.from:no-reply@my-microservice.local}") String from,
            @Value("${notification.dispatch.batch-size:10}") int batchSize) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mailSender = mailSender;
        this.from = from;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${notification.dispatch.interval:5s}")
    public void dispatchDueEmails() {
        transactions.executeWithoutResult(status -> dispatchBatch());
    }

    private void dispatchBatch() {
        List<PendingEmail> messages = jdbc.query("""
                SELECT e.id,e.template,e.order_id,e.reason,u.email,u.display_name,COALESCE(e.amount,o.total_amount) AS amount
                FROM notification_service.email_message e
                LEFT JOIN notification_service.order_ref o ON o.order_id=e.order_id
                LEFT JOIN notification_service.user_contact u ON u.user_id=o.user_id
                WHERE e.status='PENDING' AND e.next_attempt_at <= now()
                ORDER BY e.created_at,e.id
                LIMIT ? FOR UPDATE OF e SKIP LOCKED
                """, (rs, rowNum) -> new PendingEmail(rs.getObject("id", UUID.class), rs.getString("template"),
                        rs.getObject("order_id", UUID.class), rs.getString("reason"), rs.getString("email"),
                        rs.getString("display_name"), rs.getBigDecimal("amount")), batchSize);

        for (PendingEmail message : messages) {
            if (message.email() == null || message.totalAmount() == null) {
                // Keep pending while registration/order references arrive; E5 adds capped retry policy.
                jdbc.update("UPDATE notification_service.email_message SET attempts=attempts+1, "
                                + "next_attempt_at=now()+interval '10 seconds' WHERE id=? AND status='PENDING'",
                        message.id());
                continue;
            }
            try {
                send(message);
                jdbc.update("UPDATE notification_service.email_message SET status='SENT',sent_at=now(), "
                                + "attempts=attempts+1,last_error=NULL WHERE id=? AND status='PENDING'", message.id());
            } catch (MailException | jakarta.mail.MessagingException failure) {
                jdbc.update("UPDATE notification_service.email_message SET status='FAILED',attempts=attempts+1, "
                                + "last_error=? WHERE id=? AND status='PENDING'",
                        failure.getClass().getSimpleName(), message.id());
            }
        }
    }

    private void send(PendingEmail message) throws jakarta.mail.MessagingException {
        String subject = SUBJECTS.get(message.template());
        if (subject == null) throw new IllegalStateException("Unknown email template");
        String name = sanitizeName(message.displayName());
        String reason = "timeout".equals(message.reason()) ? "逾時未付款" : "已取消";
        String body = loadTemplate(message.template())
                .replace("{{name}}", name)
                .replace("{{orderId}}", String.valueOf(message.orderId()))
                .replace("{{amount}}", message.totalAmount().toPlainString())
                .replace("{{reason}}", reason);

        MimeMessage mail = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mail, false, StandardCharsets.UTF_8.name());
        helper.setFrom(from);
        helper.setTo(message.email());
        helper.setSubject(subject);
        helper.setText(body, false);
        mail.setHeader("Message-ID", "<" + message.id() + "@my-microservice.local>");
        mailSender.send(mail);
    }

    private static String loadTemplate(String template) {
        try (var input = new ClassPathResource("templates/mail/" + template + ".txt").getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to load email template", failure);
        }
    }

    private static String sanitizeName(String name) {
        if (name == null || name.isBlank()) return "顧客";
        String sanitized = name.replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").trim();
        return sanitized.isEmpty() ? "顧客" : sanitized;
    }

    private record PendingEmail(UUID id, String template, UUID orderId, String reason,
            String email, String displayName, java.math.BigDecimal totalAmount) { }
}
