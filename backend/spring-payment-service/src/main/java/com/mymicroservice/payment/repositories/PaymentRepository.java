package com.mymicroservice.payment.repositories;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mymicroservice.payment.entities.Payment;
import com.mymicroservice.payment.enums.PaymentStatus;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    // 進行中或成功的付款最多一筆（uq_payment_order_active）
    Optional<Payment> findFirstByOrderIdAndStatusIn(UUID orderId, Collection<PaymentStatus> statuses);
}
