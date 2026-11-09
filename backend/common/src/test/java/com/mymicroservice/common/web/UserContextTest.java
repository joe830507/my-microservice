package com.mymicroservice.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class UserContextTest {

    @Test
    void parseUserId_validUuidWithWhitespace_isParsed() {
        UUID id = UUID.randomUUID();

        assertThat(UserContext.parseUserId(" " + id + " ")).contains(id);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "   ", "not-a-uuid", "123" })
    void parseUserId_missingOrMalformed_isEmpty(String header) {
        assertThat(UserContext.parseUserId(header)).isEmpty();
    }

    @Test
    void parseRoles_trimsAndDropsEmptyItems() {
        assertThat(UserContext.parseRoles(" CUSTOMER , ,ADMIN,")).containsExactlyInAnyOrder("CUSTOMER", "ADMIN");
    }

    @Test
    void parseRoles_isCaseSensitive() {
        assertThat(UserContext.parseRoles("admin")).containsExactly("admin").doesNotContain("ADMIN");
    }

    @Test
    void parseRoles_null_isEmpty() {
        assertThat(UserContext.parseRoles(null)).isEmpty();
    }
}
