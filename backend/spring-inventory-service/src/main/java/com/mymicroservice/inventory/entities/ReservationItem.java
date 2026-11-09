package com.mymicroservice.inventory.entities;

import java.io.Serializable;
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

// 預扣的明細；跟著 Reservation 一起建立，建立後不會修改，所以沒有稽核欄位與 version
@Getter
@Setter
@Entity
@IdClass(ReservationItem.Key.class)
@Table(schema = "inventory_service", name = "reservation_item")
public class ReservationItem {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Reservation reservation;

    @Id
    @Column(name = "sku", nullable = false, updatable = false)
    private String sku;

    @Column(name = "qty", nullable = false, updatable = false)
    private int qty;

    // 複合主鍵 (order_id, sku)；欄位名稱要和上面的 @Id 欄位一致，reservation 對應 Reservation 的主鍵型別
    public record Key(UUID reservation, String sku) implements Serializable {
    }
}
