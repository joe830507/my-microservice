package com.mymicroservice.inventory.entities;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.Hibernate;

import com.mymicroservice.common.entity.CommonEntity;
import com.mymicroservice.inventory.enums.ReservationStatus;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

// 一張訂單一筆；orderId 是主鍵，也是預扣、確認、釋放的冪等鍵
@Getter
@Setter
@Entity
@Table(schema = "inventory_service", name = "reservation")
public class Reservation extends CommonEntity {

    // 由 order-service 指定，不自己產生
    @Id
    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReservationStatus status;

    // 不提供 setter：用 addItem 增加；釋放不存在的預扣時會建立沒有明細的紀錄
    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sku")
    @Setter(AccessLevel.NONE)
    private List<ReservationItem> items = new ArrayList<>();

    public void addItem(String sku, int qty) {
        ReservationItem item = new ReservationItem();
        item.setReservation(this);
        item.setSku(sku);
        item.setQty(qty);
        items.add(item);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) {
            return false;
        }
        Reservation other = (Reservation) o;
        return orderId != null && orderId.equals(other.getOrderId());
    }

    @Override
    public int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }
}
