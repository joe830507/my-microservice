package com.mymicroservice.inventory.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.mymicroservice.common.web.UserContextHeaders;
import com.mymicroservice.inventory.TestcontainersConfiguration;
import com.mymicroservice.inventory.repositories.StockRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

// PUT 讀出庫存之後、寫入之前，剛好有預扣改了同一個 SKU：
// 讀出後立刻在另一個交易用 SQL 模擬預扣並 commit（數量變動且 version + 1），
// PUT 的寫入要被 @Version 擋下，不能把剛扣掉的數量蓋回去
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StockControllerSetConflictIntegrationTests {
    private static final UUID SYSTEM = new UUID(0L, 0L);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @PersistenceContext
    private EntityManager entityManager;
    // repository 是介面的 JDK proxy，spy 無法呼叫真正的方法；另外建一個真正的 repository 給 spy 委派
    @MockitoSpyBean
    private StockRepository stockRepository;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE inventory_service.reservation, inventory_service.stock CASCADE");
        jdbcTemplate.update("""
                INSERT INTO inventory_service.stock (sku, available, reserved, created_by, updated_by)
                VALUES ('SKU-001', 5, 0, ?, ?)
                """, SYSTEM, SYSTEM);
    }

    @Test
    void setStock_stockChangedAfterRead_returns409AndKeepsConcurrentChange() throws Exception {
        StockRepository realRepository = new JpaRepositoryFactory(entityManager).getRepository(StockRepository.class);
        doAnswer(invocation -> {
            Object result = realRepository.findById(invocation.getArgument(0));
            TransactionTemplate concurrent = new TransactionTemplate(transactionManager);
            concurrent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            concurrent.executeWithoutResult(status -> jdbcTemplate.update("""
                    UPDATE inventory_service.stock
                    SET available = available - 2, reserved = reserved + 2, version = version + 1
                    WHERE sku = 'SKU-001'
                    """));
            return result;
        }).when(stockRepository).findById(any());

        mockMvc.perform(put("/api/v1/inventory/SKU-001")
                .header(UserContextHeaders.USER_ID, UUID.randomUUID().toString())
                .header(UserContextHeaders.USER_ROLES, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"available\": 100}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 預扣的結果保留，available 沒有被蓋成 100
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT available, reserved FROM inventory_service.stock WHERE sku = 'SKU-001'");
        assertThat(row.get("available")).isEqualTo(3);
        assertThat(row.get("reserved")).isEqualTo(2);
    }
}
