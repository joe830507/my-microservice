package com.mymicroservice.common.events;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.common.exception.ErrorCode;

class ResilientEventConsumerTest {
    private final ResilientEventConsumer consumer = new ResilientEventConsumer();

    @Test
    void classifiesByHttpStatusRegardlessOfBusinessCode() {
        AppException conflict = new AppException(new TestError(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> consumer.consume("test", "key", "{}", () -> { throw conflict; }))
                .isInstanceOf(ResilientEventConsumer.EventRejectedException.class).hasCause(conflict);

        AppException unavailable = new AppException(new TestError(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> consumer.consume("test", "key", "{}", () -> { throw unavailable; }))
                .isSameAs(unavailable);
    }

    private record TestError(HttpStatus status) implements ErrorCode {
        @Override public HttpStatus getStatus() { return status; }
        @Override public String getMessage() { return "failure"; }
        @Override public String name() { return "UNRELATED_SERVICE_ERROR"; }
    }
}
