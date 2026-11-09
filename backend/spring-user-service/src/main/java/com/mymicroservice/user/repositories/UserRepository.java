package com.mymicroservice.user.repositories;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.mymicroservice.user.entities.User;

public interface UserRepository extends JpaRepository<User, UUID> {

    // email 區分大小寫，與 DB 的 uq_users_email 一致
    boolean existsByEmail(String email);

    // 登入要把角色放進 JWT：一次 join 撈回 roles，避免延遲載入多一次查詢
    @EntityGraph(attributePaths = "roles")
    Optional<User> findByEmail(String email);
}
