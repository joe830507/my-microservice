package com.mymicroservice.user.entities;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.hibernate.Hibernate;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;

import com.mymicroservice.common.entity.CommonEntity;
import com.mymicroservice.user.enums.Role;
import com.mymicroservice.user.enums.UserStatus;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(schema = "user_service", name = "users")
public class User extends CommonEntity {

    @Id
    @UuidGenerator(style = Style.VERSION_7)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    // 不提供 setter：Hibernate 追蹤的是這個集合實例，整個換掉會導致刪光重建；增減請操作 getRoles()
    // 分頁列表不能用 join fetch 撈集合（Hibernate 會改在記憶體分頁），
    // 改用 @BatchSize：存取第一筆的 roles 時，一次查回同一頁最多 100 位使用者的角色，避免 N+1
    @BatchSize(size = 100)
    @ElementCollection
    @CollectionTable(schema = "user_service", name = "user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    @Setter(AccessLevel.NONE)
    private Set<Role> roles = new HashSet<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) {
            return false;
        }
        User other = (User) o;
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }
}
