package com.mymicroservice.order.entities;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

// 訂單明細：商品名稱、價格是下單當下的快照，商品之後改名或改價都不影響訂單
// 跟著 Order 一起建立，之後不會修改，所以沒有稽核欄位與 version
@Getter
@Setter
@Entity
@IdClass(OrderItem.Key.class)
@Table(schema = "order_service", name = "order_item")
public class OrderItem {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @Id
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "sku", nullable = false, updatable = false)
    private String sku;

    @Column(name = "product_name", nullable = false, updatable = false)
    private String productName;

    @Column(name = "unit_price", nullable = false, updatable = false)
    private BigDecimal unitPrice;

    @Column(name = "qty", nullable = false, updatable = false)
    private int qty;

    @Column(name = "subtotal", nullable = false, updatable = false)
    private BigDecimal subtotal;

    // 複合主鍵 (order_id, product_id)；欄位名稱要和上面的 @Id 欄位一致，order 對應 Order 的主鍵型別
    public record Key(UUID order, UUID productId) implements Serializable {
    }
}
