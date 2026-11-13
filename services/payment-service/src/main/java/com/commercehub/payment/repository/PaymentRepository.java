package com.commercehub.payment.repository;

import com.commercehub.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);
    Optional<Payment> findByOrderId(Long orderId);
    Optional<Payment> findFirstByOrderIdOrderByIdDesc(Long orderId);
}
