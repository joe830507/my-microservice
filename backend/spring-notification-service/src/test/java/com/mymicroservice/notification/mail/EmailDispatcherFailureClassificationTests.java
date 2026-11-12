package com.mymicroservice.notification.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;


class EmailDispatcherFailureClassificationTests {
    @Test
    void smtp5xxIsPermanentAnd4xxIsTransient() throws Exception {
        Class<?> smtpFailure = Class.forName("org.eclipse.angus.mail.smtp.SMTPSendFailedException");
        var constructor = smtpFailure.getConstructor(String.class, int.class, String.class, Exception.class, jakarta.mail.Address[].class, jakarta.mail.Address[].class, jakarta.mail.Address[].class);
        Throwable permanent = (Throwable) constructor.newInstance("RCPT TO", 550, "mailbox unavailable", null, null, null, null);
        Throwable transientFailure = (Throwable) constructor.newInstance("DATA", 451, "temporary server error", null, null, null, null);

        assertThat(EmailDispatcher.isPermanent(permanent)).isTrue();
        assertThat(EmailDispatcher.isPermanent(transientFailure)).isFalse();
    }

    @Test
    void invalidRecipientAddressIsPermanent() throws Exception {
        var failure = new jakarta.mail.internet.AddressException("invalid recipient");

        assertThat(EmailDispatcher.isPermanent(failure)).isTrue();
    }
}
