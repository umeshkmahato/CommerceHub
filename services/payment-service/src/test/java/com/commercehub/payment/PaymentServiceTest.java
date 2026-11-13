package com.commercehub.payment;

import com.commercehub.payment.entity.OutboxEvent;
import com.commercehub.payment.entity.Payment;
import com.commercehub.payment.entity.PaymentTransaction;
import com.commercehub.payment.event.PaymentEvents.InventoryReservedEvent;
import com.commercehub.payment.gateway.PaymentGateway;
import com.commercehub.payment.repository.OutboxEventRepository;
import com.commercehub.payment.repository.PaymentRepository;
import com.commercehub.payment.repository.PaymentTransactionRepository;
import com.commercehub.payment.repository.ProcessedEventRepository;
import com.commercehub.payment.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PaymentService unit tests — the money-path guarantees:
 * idempotent charge, approve/decline branches, ledger writes, outbox events.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock PaymentRepository paymentRepo;
    @Mock PaymentTransactionRepository txnRepo;
    @Mock ProcessedEventRepository processedRepo;
    @Mock OutboxEventRepository outboxRepo;
    @Mock PaymentGateway gateway;

    PaymentService paymentService;

    private InventoryReservedEvent reservedEvent;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentRepo, txnRepo, processedRepo,
                outboxRepo, gateway,
                new ObjectMapper().registerModule(
                        new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));

        reservedEvent = new InventoryReservedEvent(
                UUID.randomUUID(), "inventory.reserved", 55L,
                new BigDecimal("49.98"), 42L, List.of(), Instant.now(), 1);

        lenient().when(paymentRepo.save(any())).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            if (p.getId() == null)
                org.springframework.test.util.ReflectionTestUtils.setField(p, "id", 900L);
            return p;
        });
        lenient().when(txnRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(outboxRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void approvedCharge_capturesAndPublishesCompleted() {
        when(paymentRepo.findByIdempotencyKey("saga-55")).thenReturn(Optional.empty());
        when(gateway.charge(any(), any(), any(), any()))
                .thenReturn(new PaymentGateway.ChargeResult(true, "gw_ref_1", null));

        Payment result = paymentService.chargeForOrder(reservedEvent);

        assertEquals(Payment.Status.CAPTURED, result.getStatus());
        assertEquals("gw_ref_1", result.getGatewayRef());
        assertNotNull(result.getCompletedAt());
        verify(txnRepo).save(argThat(t ->
                t.getTxnType() == PaymentTransaction.TxnType.CAPTURE
                        && t.getStatus() == PaymentTransaction.TxnStatus.SUCCESS));
        ArgumentCaptor<OutboxEvent> cap = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).save(cap.capture());
        assertEquals("payment.completed", cap.getValue().getEventType());
    }

    @Test
    void declinedCharge_marksFailedAndPublishesFailed() {
        when(paymentRepo.findByIdempotencyKey("saga-55")).thenReturn(Optional.empty());
        when(gateway.charge(any(), any(), any(), any()))
                .thenReturn(new PaymentGateway.ChargeResult(false, "gw_ref_2", "CARD_DECLINED"));

        Payment result = paymentService.chargeForOrder(reservedEvent);

        assertEquals(Payment.Status.FAILED, result.getStatus());
        assertEquals("CARD_DECLINED", result.getFailureReason());
        ArgumentCaptor<OutboxEvent> cap = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).save(cap.capture());
        assertEquals("payment.failed", cap.getValue().getEventType());
    }

    @Test
    void replayedEvent_existingPayment_skipsGatewayCall() {
        Payment existing = new Payment();
        org.springframework.test.util.ReflectionTestUtils.setField(existing, "id", 1L);
        existing.setStatus(Payment.Status.CAPTURED);
        when(paymentRepo.findByIdempotencyKey("saga-55")).thenReturn(Optional.of(existing));

        Payment result = paymentService.chargeForOrder(reservedEvent);

        assertSame(existing, result);
        verify(gateway, never()).charge(any(), any(), any(), any());   // NO double charge
        verify(outboxRepo, never()).save(any());
    }

    @Test
    void refund_capturedPayment_writesLedgerAndEvent() {
        Payment p = new Payment();
        org.springframework.test.util.ReflectionTestUtils.setField(p, "id", 5L);
        p.setOrderId(55L);
        p.setAmount(new BigDecimal("49.98"));
        p.setStatus(Payment.Status.CAPTURED);
        p.setGatewayRef("gw_ref_1");
        p.setIdempotencyKey("saga-55");
        when(paymentRepo.findById(5L)).thenReturn(Optional.of(p));
        when(gateway.refund(any(), any(), any()))
                .thenReturn(new PaymentGateway.RefundResult(true, "rf_1", null));

        Payment result = paymentService.refund(5L, "ORDER_CANCELLED");

        assertEquals(Payment.Status.REFUNDED, result.getStatus());
        verify(txnRepo).save(argThat(t ->
                t.getTxnType() == PaymentTransaction.TxnType.REFUND));
        verify(outboxRepo).save(argThat(e -> e.getEventType().equals("payment.refunded")));
    }

    @Test
    void refund_uncapturedPayment_rejected() {
        Payment p = new Payment();
        org.springframework.test.util.ReflectionTestUtils.setField(p, "id", 5L);
        p.setStatus(Payment.Status.FAILED);
        when(paymentRepo.findById(5L)).thenReturn(Optional.of(p));

        assertThrows(IllegalStateException.class, () -> paymentService.refund(5L, "x"));
        verify(gateway, never()).refund(any(), any(), any());
    }
}
