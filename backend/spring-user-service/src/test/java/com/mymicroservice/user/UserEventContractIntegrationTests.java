package com.mymicroservice.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mymicroservice.user.dtos.register.RegisterReqDto;
import com.mymicroservice.user.service.AuthService;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false",
        "app.events.outbox-poll-interval=3600000"})
@Import(TestcontainersConfiguration.class)
class UserEventContractIntegrationTests {
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @Test
    void registrationEventContainsContractFieldsAndNoPassword() {
        String email = UUID.randomUUID() + "@example.com";
        auth.register(new RegisterReqDto(email, "password-123", "Event User"));
        String json = jdbc.queryForObject("SELECT payload::text FROM user_service.outbox_event WHERE payload->'payload'->>'email' = ?", String.class, email);
        var envelope = mapper.readTree(json);
        assertThat(envelope.get("type").asText()).isEqualTo("UserRegistered");
        assertThat(envelope.get("version").asInt()).isEqualTo(1);
        var payload = envelope.get("payload");
        assertThat(payload.size()).isEqualTo(3);
        assertThat(payload.get("userId").asText()).isEqualTo(envelope.get("aggregateId").asText());
        assertThat(payload.get("email").asText()).isEqualTo(email);
        assertThat(payload.get("displayName").asText()).isEqualTo("Event User");
        assertThat(json).doesNotContain("password", "passwordHash");
    }
}
