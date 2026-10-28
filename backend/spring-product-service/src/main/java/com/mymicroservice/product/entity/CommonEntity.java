package com.mymicroservice.product.entity;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Getter;

@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class CommonEntity {
    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;
    @LastModifiedDate
    private Instant updatedAt;
    @CreatedBy
    @Column(updatable = false)
    private UUID createdBy;
    @LastModifiedBy
    private UUID updatedBy;
    @Version
    private Long version;
}
