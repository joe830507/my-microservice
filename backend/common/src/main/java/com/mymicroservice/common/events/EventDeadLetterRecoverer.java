package com.mymicroservice.common.events;

import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;

// 所有事件 listener 共用相同的 DLT topic/partition 規則，且只在送達 broker 後計數。
public class EventDeadLetterRecoverer {
    private final DeadLetterPublishingRecoverer recoverer;
    private final Counter published;

    public EventDeadLetterRecoverer(KafkaTemplate<String, String> template, MeterRegistry registry) {
        this.published = registry.counter("events.dlt.published");
        this.recoverer = new DeadLetterPublishingRecoverer(template,
                (record, failure) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        this.recoverer.setFailIfSendResultIsError(true);
    }

    public void accept(ConsumerRecord<?, ?> record, Exception failure) {
        recoverer.accept(record, failure);
        published.increment();
    }
}
