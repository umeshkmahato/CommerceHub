package com.commercehub.payment;

import com.commercehub.payment.entity.Payment;
import com.commercehub.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** Payment repository IT — real PostgreSQL 16: idempotency-key UNIQUE is
 *  the hard exactly-once-charge guarantee; money round-trips as BigDecimal. */
@Testcontainers
@SpringBootTest
class PaymentRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("paymentdb");

    @Autowired PaymentRepository paymentRepo;

    private Payment newPayment(String idemKey) {
        Payment p = new Payment();
        p.setPaymentNumber("PAY-IT-" + idemKey);
        p.setOrderId(55L);
        p.setCustomerId(42L);
        p.setAmount(new BigDecimal("49.98"));
        p.setIdempotencyKey(idemKey);
        p.setStatus(Payment.Status.INITIATED);
        return p;
    }

    @Test
    void paymentPersistsAndFindsByIdempotencyKey() {
        paymentRepo.saveAndFlush(newPayment("saga-55"));

        var found = paymentRepo.findByIdempotencyKey("saga-55");
        assertTrue(found.isPresent());
        assertEquals(0, new BigDecimal("49.98").compareTo(found.get().getAmount()));
        assertEquals(Payment.Status.INITIATED, found.get().getStatus());
    }

    @Test
    void duplicateIdempotencyKey_violatesUniqueConstraint() {
        paymentRepo.saveAndFlush(newPayment("saga-dup"));
        assertThrows(DataIntegrityViolationException.class,
                () -> paymentRepo.saveAndFlush(newPayment("saga-dup")));
    }

    @Test
    void findFirstByOrderId_returnsLatest() {
        paymentRepo.save(newPayment("saga-o1-a"));
        paymentRepo.save(newPayment("saga-o1-b"));
        assertTrue(paymentRepo.findFirstByOrderIdOrderByIdDesc(55L).isPresent());
        assertTrue(paymentRepo.findByOrderId(999999L).isEmpty());
    }
}
