package com.mymicroservice.inventory.entities;

import org.hibernate.Hibernate;

import com.mymicroservice.common.entity.CommonEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

// 一個 SKU 一筆；預扣、確認、釋放的數量變動用 StockRepository 的條件式 UPDATE，不透過實體修改
@Getter
@Setter
@Entity
@Table(schema = "inventory_service", name = "stock")
public class Stock extends CommonEntity {

    // 自然鍵：由後台指定，和 product 的 sku 相同，不另外產生 id
    @Id
    @Column(name = "sku", nullable = false, updatable = false)
    private String sku;

    // 可賣的數量，不含已預扣的
    @Column(name = "available", nullable = false)
    private int available;

    // 已預扣、等待付款的數量
    @Column(name = "reserved", nullable = false)
    private int reserved;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) {
            return false;
        }
        Stock other = (Stock) o;
        return sku != null && sku.equals(other.getSku());
    }

    @Override
    public int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }
}
