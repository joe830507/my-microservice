package com.mymicroservice.notification.mail;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.mail.internet.MimeMessage;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import com.mymicroservice.notification.config.NotificationProperties;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;

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
    private final NotificationProperties properties;
    private final EmailMetrics metrics;
    private final OpenTelemetry telemetry;

    public EmailDispatcher(JdbcTemplate jdbc, TransactionTemplate transactions, JavaMailSender mailSender,
            NotificationProperties properties, EmailMetrics metrics, ObjectProvider<OpenTelemetry> telemetry) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mailSender = mailSender;
        this.properties = properties;
        this.metrics = metrics;
        this.telemetry = telemetry.getIfAvailable(OpenTelemetry::noop);
    }

    @Scheduled(fixedDelayString = "${notification.dispatch.interval:5s}")
    public void dispatchDueEmails() {
        transactions.executeWithoutResult(status -> dispatchBatch());
    }

    private void dispatchBatch() {
        List<PendingEmail> messages = jdbc.query("""
                SELECT e.id,e.template,e.order_id,e.reason,u.email,u.display_name,
                       o.order_id AS resolved_order_id,u.user_id AS resolved_user_id,
                       COALESCE(e.amount,o.total_amount) AS amount,e.attempts
                FROM notification_service.email_message e
                LEFT JOIN notification_service.order_ref o ON o.order_id=e.order_id
                LEFT JOIN notification_service.user_contact u ON u.user_id=o.user_id
                WHERE e.status='PENDING' AND e.next_attempt_at <= now()
                ORDER BY e.created_at,e.id
                LIMIT ? FOR UPDATE OF e SKIP LOCKED
                """, (rs, rowNum) -> new PendingEmail(rs.getObject("id", UUID.class), rs.getString("template"),
                        rs.getObject("order_id", UUID.class), rs.getString("reason"), rs.getString("email"),
                        rs.getString("display_name"), rs.getBigDecimal("amount"),
                        rs.getObject("resolved_order_id", UUID.class), rs.getObject("resolved_user_id", UUID.class),
                        rs.getInt("attempts")), properties.dispatch().batchSize());

        for (PendingEmail message : messages) {
            if (message.resolvedOrderId() == null || message.resolvedUserId() == null || message.email() == null) {
                retryMissingRecipient(message);
                continue;
            }
            try {
                sendTraced(message);
                jdbc.update("UPDATE notification_service.email_message SET status='SENT',sent_at=now(), "
                                + "attempts=attempts+1,last_error=NULL WHERE id=? AND status='PENDING'", message.id());
                metrics.result(message.template(), "sent");
            } catch (Exception failure) {
                retryOrFail(message, failure);
            }
        }
    }

    private void retryMissingRecipient(PendingEmail message) {
        int attempt = message.attempts() + 1;
        String reason = message.resolvedOrderId() == null ? "unknown_order" : "unknown_user";
        if (attempt >= properties.dispatch().missingRecipientMaxAttempts()) {
            jdbc.update("UPDATE notification_service.email_message SET status='SKIPPED',attempts=?, "
                            + "skip_reason=?,last_error=NULL WHERE id=? AND status='PENDING'",
                    attempt, reason, message.id());
            metrics.result(message.template(), "skipped");
            return;
        }
        long delaySeconds = retryDelay(attempt).toSeconds();
        jdbc.update("UPDATE notification_service.email_message SET attempts=?, "
                        + "next_attempt_at=now()+(? * interval '1 second') WHERE id=? AND status='PENDING'",
                attempt, delaySeconds, message.id());
        metrics.result(message.template(), "retry");
    }

    private void retryOrFail(PendingEmail message, Exception failure) {
        int attempt = message.attempts() + 1;
        boolean permanent = isPermanent(failure);
        if (permanent || attempt >= properties.dispatch().retryDelays().size()) {
            jdbc.update("UPDATE notification_service.email_message SET status='FAILED',attempts=?,last_error=? "
                            + "WHERE id=? AND status='PENDING'",
                    attempt, failure.getClass().getSimpleName(), message.id());
            metrics.result(message.template(), "failed");
            return;
        }
        long delaySeconds = retryDelay(attempt).toSeconds();
        jdbc.update("UPDATE notification_service.email_message SET attempts=?,last_error=?, "
                        + "next_attempt_at=now()+(? * interval '1 second') WHERE id=? AND status='PENDING'",
                attempt, failure.getClass().getSimpleName(), delaySeconds, message.id());
        metrics.result(message.template(), "retry");
    }

    private java.time.Duration retryDelay(int attempt) {
        List<java.time.Duration> delays = properties.dispatch().retryDelays();
        return delays.get(Math.min(Math.max(attempt - 1, 0), delays.size() - 1));
    }

    static boolean isPermanent(Throwable failure) {
        java.util.ArrayDeque<Throwable> pending = new java.util.ArrayDeque<>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            String name = current.getClass().getSimpleName();
            if (name.equals("MailAuthenticationException") || name.equals("MailParseException")
                    || name.equals("MailPreparationException") || name.equals("SMTPAddressFailedException")) {
                return true;
            }
            if (name.equals("SMTPSendFailedException")) {
                try {
                    Object code = current.getClass().getMethod("getReturnCode").invoke(current);
                    if (code instanceof Number number && number.intValue() >= 500) return true;
                } catch (ReflectiveOperationException ignored) {
                    // Unknown SMTP response is treated as transient.
                }
            }
            if (current.getCause() != null) pending.addLast(current.getCause());
            if (current instanceof jakarta.mail.MessagingException messaging
                    && messaging.getNextException() != null) pending.addLast(messaging.getNextException());
            if (current instanceof org.springframework.mail.MailSendException mailFailure) {
                mailFailure.getFailedMessages().values().forEach(pending::addLast);
            }
        }
        return false;
    }

    private void sendTraced(PendingEmail message) throws jakarta.mail.MessagingException {
        Span span = telemetry.getTracer("notification-service")
                .spanBuilder("notification.email.send")
                .startSpan();
        long started = System.nanoTime();
        try (Scope ignored = span.makeCurrent()) {
            span.setAttribute("template", message.template());
            send(message);
        } catch (jakarta.mail.MessagingException | RuntimeException failure) {
            span.recordException(failure);
            span.setStatus(StatusCode.ERROR);
            throw failure;
        } finally {
            metrics.sendDuration(message.template(), System.nanoTime() - started);
            span.end();
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
        helper.setFrom(properties.mail().from());
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
            String email, String displayName, java.math.BigDecimal totalAmount,
            UUID resolvedOrderId, UUID resolvedUserId, int attempts) { }
}
