package com.mymicroservice.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mymicroservice.product.dto.product.addOneNewProduct.AddOneNewProductReq;
import com.mymicroservice.product.dto.product.updateOneProduct.UpdateOneProductReq;
import com.mymicroservice.product.dto.product.changeProductStatus.ChangeProductStatusReq;
import com.mymicroservice.product.service.ProductService;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false",
        "app.events.outbox-poll-interval=3600000"})
@Import(TestcontainersConfiguration.class)
class ProductEventContractIntegrationTests {
    @Autowired ProductService products;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @Test
    void createdProductEventCarriesSnapshotIncludingOptionalFields() {
        String sku = "EVENT-" + UUID.randomUUID();
        var created = products.addOneNewProduct(new AddOneNewProductReq(sku, "Event Product", null, null, null, new BigDecimal("12.34"), null));
        String json = jdbc.queryForObject("SELECT payload::text FROM product_service.outbox_event WHERE payload->'payload'->>'sku' = ?", String.class, sku);
        var envelope = mapper.readTree(json);
        assertThat(envelope.get("type").asText()).isEqualTo("ProductCreated");
        assertThat(envelope.get("version").asInt()).isEqualTo(1);
        var payload = envelope.get("payload");
        assertThat(payload.size()).isEqualTo(8);
        assertThat(payload.get("productId").asText()).isEqualTo(envelope.get("aggregateId").asText());
        assertThat(payload.get("sku").asText()).isEqualTo(sku);
        assertThat(payload.get("status").asText()).isEqualTo("DRAFT");
        assertThat(payload.get("price").decimalValue()).isEqualByComparingTo("12.34");
        assertThat(payload.has("description")).isTrue();
        assertThat(payload.has("categoryId")).isTrue();
        assertThat(payload.has("version")).isTrue();
        var updated = products.updateOneProduct(created.id(), new UpdateOneProductReq(sku, "Updated Product",
                "Updated description", null, null, new BigDecimal("23.45"), null, created.version()));
        products.archiveProduct(created.id(), new ChangeProductStatusReq(updated.version()));
        var events = jdbc.queryForList("SELECT payload::text FROM product_service.outbox_event WHERE aggregate_id = ? ORDER BY sequence_id", String.class, created.id().toString());
        assertThat(events).hasSize(3);
        var update = mapper.readTree(events.get(1));
        assertThat(update.get("type").asText()).isEqualTo("ProductUpdated");
        assertThat(update.get("payload").get("name").asText()).isEqualTo("Updated Product");
        assertThat(update.get("payload").get("version").asLong()).isEqualTo(updated.version());
        var archived = mapper.readTree(events.get(2));
        assertThat(archived.get("type").asText()).isEqualTo("ProductStatusChanged");
        assertThat(archived.get("payload").get("status").asText()).isEqualTo("ARCHIVED");
        for (String event : events) assertThat(mapper.readTree(event).get("payload").size()).isEqualTo(8);
    }
}
