package com.mymicroservice.notification.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(Mail mail, Dispatch dispatch) {
    public NotificationProperties {
        mail = mail == null ? new Mail("no-reply@my-microservice.local") : mail;
        dispatch = dispatch == null ? Dispatch.defaults() : dispatch;
    }

    public record Mail(String from) { }

    public record Dispatch(Duration interval, int batchSize, List<Duration> retryDelays,
            int missingRecipientMaxAttempts, int smtpConnectTimeoutMs,
            int smtpReadTimeoutMs, int smtpWriteTimeoutMs) {
        public Dispatch {
            interval = interval == null ? Duration.ofSeconds(5) : interval;
            batchSize = batchSize <= 0 ? 10 : batchSize;
            retryDelays = retryDelays == null || retryDelays.isEmpty()
                    ? defaults().retryDelays() : List.copyOf(retryDelays);
            missingRecipientMaxAttempts = missingRecipientMaxAttempts <= 0 ? 5 : missingRecipientMaxAttempts;
            smtpConnectTimeoutMs = smtpConnectTimeoutMs <= 0 ? 3000 : smtpConnectTimeoutMs;
            smtpReadTimeoutMs = smtpReadTimeoutMs <= 0 ? 5000 : smtpReadTimeoutMs;
            smtpWriteTimeoutMs = smtpWriteTimeoutMs <= 0 ? 5000 : smtpWriteTimeoutMs;
        }

        private static Dispatch defaults() {
            return new Dispatch(Duration.ofSeconds(5), 10,
                    List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(1),
                            Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(10),
                            Duration.ofMinutes(30), Duration.ofHours(1)),
                    5, 3000, 5000, 5000);
        }
    }
}
