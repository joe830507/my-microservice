package com.mymicroservice.common.events;

import java.time.Instant;
import java.util.UUID;

public record EventEnvelope<T>(UUID eventId, String type, int version, Instant occurredAt,
        String aggregateId, T payload) {
}
