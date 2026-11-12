package com.mymicroservice.notification.mail;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.MimeMessage;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
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
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JavaMailSender mailSender;
    private final NotificationProperties properties;
    private final EmailMetrics metrics;
    private final EmailTemplateRenderer templates;
    private final OpenTelemetry telemetry;

    public EmailDispatcher(JdbcTemplate jdbc, TransactionTemplate transactions, JavaMailSender mailSender,
            NotificationProperties properties, EmailMetrics metrics, EmailTemplateRenderer templates,
            ObjectProvider<OpenTelemetry> telemetry) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mailSender = mailSender;
        this.properties = properties;
        this.metrics = metrics;
        this.templates = templates;
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

    private Duration retryDelay(int attempt) {
        List<Duration> delays = properties.dispatch().retryDelays();
        return delays.get(Math.min(Math.max(attempt - 1, 0), delays.size() - 1));
    }

    // 收件地址無效、認證失敗、SMTP 5xx 屬永久性錯誤，重試沒有意義；其餘（連線、逾時、4xx）視為暫時性。
    // SMTP 實作類別（org.eclipse.angus.mail.smtp）只在執行期classpath，編譯期看不到，所以這兩個類別以名稱判斷，
    // 其他例外型別一律用 instanceof。
    static boolean isPermanent(Throwable failure) {
        Deque<Throwable> pending = new ArrayDeque<>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (current instanceof MailAuthenticationException || current instanceof MailParseException
                    || current instanceof MailPreparationException || current instanceof AddressException
                    || current.getClass().getSimpleName().equals("SMTPAddressFailedException")) {
                return true;
            }
            if (isSmtpFiveHundred(current)) {
                return true;
            }
            if (current.getCause() != null) {
                pending.addLast(current.getCause());
            }
            if (current instanceof MessagingException messaging && messaging.getNextException() != null) {
                pending.addLast(messaging.getNextException());
            }
            if (current instanceof MailSendException mailFailure) {
                pending.addAll(mailFailure.getFailedMessages().values());
            }
        }
        return false;
    }

    private static boolean isSmtpFiveHundred(Throwable failure) {
        if (!failure.getClass().getSimpleName().equals("SMTPSendFailedException")) {
            return false;
        }
        try {
            Object code = failure.getClass().getMethod("getReturnCode").invoke(failure);
            return code instanceof Number number && number.intValue() >= 500;
        } catch (ReflectiveOperationException unknownResponse) {
            return false;
        }
    }

    private void sendTraced(PendingEmail message) throws MessagingException {
        Span span = telemetry.getTracer("notification-service")
                .spanBuilder("notification.email.send")
                .startSpan();
        long started = System.nanoTime();
        try (Scope ignored = span.makeCurrent()) {
            span.setAttribute("template", message.template());
            send(message);
        } catch (MessagingException | RuntimeException failure) {
            span.recordException(failure);
            span.setStatus(StatusCode.ERROR);
            throw failure;
        } finally {
            metrics.sendDuration(message.template(), System.nanoTime() - started);
            span.end();
        }
    }

    private void send(PendingEmail message) throws MessagingException {
        MimeMessage mail = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mail, false, StandardCharsets.UTF_8.name());
        helper.setFrom(properties.mail().from());
        helper.setTo(message.email());
        helper.setSubject(templates.subject(message.template()));
        helper.setText(templates.body(message.template(), message.displayName(), message.orderId(),
                message.totalAmount(), message.reason()), false);
        // JavaMailSenderImpl 在 saveChanges 之後會還原這個值，所以重寄時 Message-ID 相同
        mail.setHeader("Message-ID", "<" + message.id() + "@my-microservice.local>");
        mailSender.send(mail);
    }

    private record PendingEmail(UUID id, String template, UUID orderId, String reason,
            String email, String displayName, BigDecimal totalAmount,
            UUID resolvedOrderId, UUID resolvedUserId, int attempts) { }
}
