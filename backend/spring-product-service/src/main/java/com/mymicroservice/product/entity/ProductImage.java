package com.mymicroservice.product.entity;

import java.util.UUID;

import org.hibernate.Hibernate;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(schema = "product_service", name = "product_image")
public class ProductImage extends CommonEntity {

    @Id
    @UuidGenerator(style = Style.VERSION_7)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "url", nullable = false)
    private String url;

    @Column(name = "alt_text")
    private String altText;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "is_primary", nullable = false)
    private Boolean isPrimary = Boolean.FALSE;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) {
            return false;
        }
        ProductImage other = (ProductImage) o;
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }
}
