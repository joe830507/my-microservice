package com.mymicroservice.common.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class ProcessedEventHandlerTest {
    @Test
    void duplicateEventDoesNotRunBusinessUpdateTwice() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transactions = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        when(jdbc.update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(java.util.UUID.class),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(1, 0);
        ProcessedEventHandler handler = new ProcessedEventHandler(jdbc, transactions, "order_service");
        AtomicInteger updates = new AtomicInteger();
        var eventId = java.util.UUID.randomUUID();

        assertThat(handler.handle(eventId, "OrderPaid", updates::incrementAndGet)).isTrue();
        assertThat(handler.handle(eventId, "OrderPaid", updates::incrementAndGet)).isFalse();
        assertThat(updates).hasValue(1);
        verify(jdbc, org.mockito.Mockito.times(2)).update(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(java.util.UUID.class), org.mockito.ArgumentMatchers.anyString());
    }
}
