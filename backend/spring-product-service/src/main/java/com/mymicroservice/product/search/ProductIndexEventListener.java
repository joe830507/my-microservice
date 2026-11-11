package com.mymicroservice.product.search;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.mymicroservice.common.events.ResilientEventConsumer;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 索引消費採快照覆寫與 ES 外部版本，不寫 processed_event；重送和亂序由 ES 版本保護。
@Component
public class ProductIndexEventListener {
    private final ObjectMapper objectMapper;
    private final ProductIndexManager indexManager;
    private final ResilientEventConsumer resilient;
    private final MeterRegistry meterRegistry;

    public ProductIndexEventListener(ObjectMapper objectMapper, ProductIndexManager indexManager,
            ResilientEventConsumer resilient, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.indexManager = indexManager;
        this.resilient = resilient;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(topics = "product.events", groupId = "product-service-search",
            containerFactory = "productSearchKafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record) throws Exception {
        resilient.consume(record, () -> index(record.value()));
    }

    void index(String json) throws Exception {
        JsonNode event = objectMapper.readTree(json);
        String type = requiredText(event, "type");
        if (!type.equals("ProductCreated") && !type.equals("ProductUpdated")
                && !type.equals("ProductStatusChanged")) {
            throw new IllegalArgumentException("Unsupported product event type " + type);
        }

        JsonNode payload = event.get("payload");
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("Product event payload must be an object");
        }
        String productId = requiredText(payload, "productId");
        try {
            UUID.fromString(productId);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid productId in product event", failure);
        }
        if (!productId.equals(requiredText(event, "aggregateId"))) {
            throw new IllegalArgumentException("Product event aggregateId does not match productId");
        }

        JsonNode priceNode = payload.get("price");
        JsonNode versionNode = payload.get("version");
        if (priceNode == null || !priceNode.isNumber() || versionNode == null || !versionNode.isIntegralNumber()
                || versionNode.longValue() < 0) {
            throw new IllegalArgumentException("Product event price or product version is invalid");
        }
        String createdAt = optionalText(payload, "createdAt");
        if (createdAt != null) {
            try {
                createdAt = Instant.parse(createdAt).toString();
            } catch (DateTimeParseException failure) {
                throw new IllegalArgumentException("Invalid createdAt in product event", failure);
            }
        }

        ProductDocument document = new ProductDocument(
                productId,
                requiredText(payload, "sku"),
                requiredText(payload, "name"),
                optionalText(payload, "brand", ""),
                optionalText(payload, "description", ""),
                optionalText(payload, "categoryId", ""),
                requiredText(payload, "status"),
                priceNode.decimalValue().setScale(2),
                createdAt);

        try {
            long indexedVersion = indexManager.write(document, versionNode.longValue());
            String result = indexedVersion < 0 ? "stale_skipped" : "indexed";
            meterRegistry.counter("search.index.events", "result", result).increment();
        } catch (ElasticsearchException failure) {
            if (failure.status() == 429 || failure.status() >= 500 || isMissingIndex(failure.error())) {
                throw new ProductIndexingTransientException(failure);
            }
            throw new IllegalArgumentException("Elasticsearch rejected product event document", failure);
        } catch (IOException failure) {
            throw new ProductIndexingTransientException(failure);
        }
    }

    private static boolean isMissingIndex(co.elastic.clients.elasticsearch._types.ErrorCause cause) {
        if (cause == null) {
            return false;
        }
        if ("index_not_found_exception".equals(cause.type()) || "alias_not_found_exception".equals(cause.type())
                || "alias_missing_exception".equals(cause.type())) {
            return true;
        }
        if (isMissingIndex(cause.causedBy())) {
            return true;
        }
        return cause.rootCause().stream().anyMatch(ProductIndexEventListener::isMissingIndex);
    }

    private static String requiredText(JsonNode object, String field) {
        String value = optionalText(object, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing product event field " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new IllegalArgumentException("Product event field " + field + " must be text");
        }
        return value.asText();
    }

    private static String optionalText(JsonNode object, String field, String fallback) {
        String value = optionalText(object, field);
        return value == null ? fallback : value;
    }
}
