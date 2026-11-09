package com.mymicroservice.inventory.repositories;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mymicroservice.inventory.entities.Stock;

// 數量變動一律用條件式 UPDATE：檢查與扣減在同一個 SQL 完成，併發時不會超賣
// 批次 UPDATE 不經過實體，@Version 與稽核欄位要自己更新；version + 1 讓同時在編輯的 PUT 收到 409
public interface StockRepository extends JpaRepository<Stock, String> {

    // 預扣：available 夠才扣，回傳 1 = 成功、0 = 庫存不足或 SKU 不存在
    @Modifying
    @Query("""
            UPDATE Stock s
            SET s.available = s.available - :qty, s.reserved = s.reserved + :qty,
                s.version = s.version + 1, s.updatedAt = :now, s.updatedBy = :updatedBy
            WHERE s.sku = :sku AND s.available >= :qty
            """)
    int reserve(@Param("sku") String sku, @Param("qty") int qty, @Param("now") Instant now,
            @Param("updatedBy") UUID updatedBy);

    // 確認扣除（付款成功）：預扣時 available 已經扣過，這裡只減少 reserved
    // 回傳 0 代表 reserved 比預扣明細少，資料不一致
    @Modifying
    @Query("""
            UPDATE Stock s
            SET s.reserved = s.reserved - :qty,
                s.version = s.version + 1, s.updatedAt = :now, s.updatedBy = :updatedBy
            WHERE s.sku = :sku AND s.reserved >= :qty
            """)
    int confirmReserved(@Param("sku") String sku, @Param("qty") int qty, @Param("now") Instant now,
            @Param("updatedBy") UUID updatedBy);
}
