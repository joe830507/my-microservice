package com.mymicroservice.common.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.common.exception.CommonErrorCode;

import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.ExponentialBackOff;

class EventsAutoConfigurationTest {
    static Stream<Throwable> transientFailures() {
        return Stream.of(new SQLTransientException("temporary"), new SQLRecoverableException("recoverable"),
                new RecoverableDataAccessException("recoverable"), new TransientDataAccessResourceException("temporary"),
                new DataAccessResourceFailureException("offline"), new CannotCreateTransactionException("offline"),
                new AppException(CommonErrorCode.INTERNAL_ERROR));
    }

    @ParameterizedTest
    @MethodSource("transientFailures")
    void rawAndWrappedTransientFailuresUseUnboundedExponentialBackoff(Throwable failure) {
        for (Throwable candidate : new Throwable[]{failure, new ListenerExecutionFailedException("listener", failure)}) {
            var backOff = EventsAutoConfiguration.retryBackOff(candidate);
            assertThat(backOff).isInstanceOf(ExponentialBackOff.class);
            var execution = backOff.start();
            assertThat(execution.nextBackOff()).isEqualTo(1000);
            assertThat(execution.nextBackOff()).isEqualTo(2000);
            for (int i = 0; i < 100; i++) assertThat(execution.nextBackOff()).isBetween(1000L, 30000L);
        }
    }

    @Test
    void unknownFailureRetainsFiniteRetryBudget() {
        var execution = EventsAutoConfiguration.retryBackOff(new IllegalStateException("unknown")).start();
        assertThat(execution.nextBackOff()).isEqualTo(500);
        assertThat(execution.nextBackOff()).isEqualTo(500);
        assertThat(execution.nextBackOff()).isEqualTo(500);
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }
}
