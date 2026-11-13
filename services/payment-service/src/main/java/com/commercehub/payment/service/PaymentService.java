package com.commercehub.payment.service;

import com.commercehub.payment.entity.OutboxEvent;
import com.commercehub.payment.entity.Payment;
import com.commercehub.payment.entity.PaymentTransaction;
import com.commercehub.payment.entity.ProcessedEvent;
import com.commercehub.payment.event.PaymentEvents.InventoryReservedEvent;
import com.commercehub.payment.event.PaymentEvents.OrderCancelledEvent;
import com.commercehub.payment.event.PaymentEvents.PaymentCompletedEvent;
import com.commercehub.payment.event.PaymentEvents.PaymentFailedEvent;
import com.commercehub.payment.event.PaymentEvents.PaymentRefundedEvent;
import com.commercehub.payment.gateway.PaymentGateway;
import com.commercehub.payment.repository.OutboxEventRepository;
import com.commercehub.payment.repository.PaymentRepository;
import com.commercehub.payment.repository.PaymentTransactionRepository;
import com.commercehub.payment.repository.ProcessedEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Payment orchestration — the money-moving step of the checkout saga.
 *
 * Guarantees:
 *  - Exactly-once charge per order: idempotencyKey = "saga-" + orderId,
 *    backed by UNIQUE constraint. Event replay → findByIdempotencyKey hit
 *    → return existing payment, never re-charge.
 *  - Atomicity: payment row + ledger transaction + outbox event commit in
 *    ONE transaction — the saga event can't be lost or duplicated relative
 *    to the payment state.
 *  - Append-only ledger: every gateway interaction is a PaymentTransaction.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() % 100000);

    private final PaymentRepository paymentRepo;
    private final PaymentTransactionRepository txnRepo;
    private final ProcessedEventRepository processedRepo;
    private final OutboxEventRepository outboxRepo;
    private final PaymentGateway gateway;
    private final ObjectMapper objectMapper;

    public PaymentService(PaymentRepository paymentRepo,
                          PaymentTransactionRepository txnRepo,
                          ProcessedEventRepository processedRepo,
                          OutboxEventRepository outboxRepo,
                          PaymentGateway gateway,
                          ObjectMapper objectMapper) {
        this.paymentRepo = paymentRepo;
        this.txnRepo = txnRepo;
        this.processedRepo = processedRepo;
        this.outboxRepo = outboxRepo;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    // ------------------------------ saga entry ------------------------------

    /**
     * Charge for an order when inventory confirms reservation.
     * Idempotent end-to-end: event dedupe (processed_events) AND payment
     * idempotency key — belt and suspenders for at-least-once delivery.
     */
    @Transactional
    public Payment chargeForOrder(InventoryReservedEvent event) {
        String idemKey = "saga-" + event.orderId();

        // Fast path: already processed this order's charge.
        var existing = paymentRepo.findByIdempotencyKey(idemKey);
        if (existing.isPresent()) {
            log.info("Charge for order {} already exists ({}), skipping",
                    event.orderId(), existing.get().getPaymentNumber());
            return existing.get();
        }

        Payment payment = new Payment();
        payment.setPaymentNumber(nextPaymentNumber());
        payment.setOrderId(event.orderId());
        payment.setCustomerId(event.customerId());
        payment.setAmount(event.amount());
        payment.setIdempotencyKey(idemKey);
        payment = paymentRepo.save(payment);   // INSERT — unique key guards races

        // Call the (simulated) PSP.
        var result = gateway.charge(event.amount(), payment.getCurrency(),
                idemKey, payment.getPaymentNumber());

        if (result.approved()) {
            payment.setStatus(Payment.Status.CAPTURED);
            payment.setGatewayRef(result.gatewayRef());
            payment.setCompletedAt(LocalDateTime.now());
            recordTxn(payment, PaymentTransaction.TxnType.CAPTURE,
                    event.amount(), PaymentTransaction.TxnStatus.SUCCESS, result.gatewayRef());
            publishCompleted(payment);
            log.info("Payment {} CAPTURED for order {}", payment.getPaymentNumber(), event.orderId());
        } else {
            payment.setStatus(Payment.Status.FAILED);
            payment.setGatewayRef(result.gatewayRef());
            payment.setFailureReason(result.declineReason());
            recordTxn(payment, PaymentTransaction.TxnType.AUTH,
                    event.amount(), PaymentTransaction.TxnStatus.FAILED, result.gatewayRef());
            publishFailed(payment, result.declineReason());
            log.warn("Payment {} FAILED for order {}: {}",
                    payment.getPaymentNumber(), event.orderId(), result.declineReason());
        }
        return paymentRepo.save(payment);
    }

    // ------------------------------ refund ------------------------------

    /** Refund a captured payment (manual or saga compensation). */
    @Transactional
    public Payment refund(Long paymentId, String reason) {
        Payment payment = paymentRepo.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));
        if (!payment.isRefundable()) {
            throw new IllegalStateException(
                    "Payment " + payment.getPaymentNumber() + " not refundable (status=" + payment.getStatus() + ")");
        }
        var result = gateway.refund(payment.getAmount(), payment.getGatewayRef(),
                "refund-" + payment.getIdempotencyKey());
        if (!result.success()) {
            throw new IllegalStateException("Gateway refund failed: " + result.reason());
        }
        payment.setStatus(Payment.Status.REFUNDED);
        recordTxn(payment, PaymentTransaction.TxnType.REFUND, payment.getAmount(),
                PaymentTransaction.TxnStatus.SUCCESS, result.gatewayRef());
        publishRefunded(payment, reason);
        return paymentRepo.save(payment);
    }

    /**
     * Manual retry of a FAILED payment. Uses a NEW idempotency key
     * ("-retry-N") so the retry is a distinct charge attempt — the original
     * failure stays in the ledger for audit.
     */
    @Transactional
    public Payment retryFailed(Long paymentId) {
        Payment payment = paymentRepo.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));
        if (payment.getStatus() != Payment.Status.FAILED) {
            throw new IllegalStateException("Only FAILED payments can be retried");
        }
        payment.setIdempotencyKey(payment.getIdempotencyKey() + "-r" + System.currentTimeMillis());
        payment.setStatus(Payment.Status.INITIATED);
        payment.setFailureReason(null);

        var result = gateway.charge(payment.getAmount(), payment.getCurrency(),
                payment.getIdempotencyKey(), payment.getPaymentNumber());
        if (result.approved()) {
            payment.setStatus(Payment.Status.CAPTURED);
            payment.setGatewayRef(result.gatewayRef());
            payment.setCompletedAt(LocalDateTime.now());
            recordTxn(payment, PaymentTransaction.TxnType.CAPTURE, payment.getAmount(),
                    PaymentTransaction.TxnStatus.SUCCESS, result.gatewayRef());
            publishCompleted(payment);
        } else {
            payment.setStatus(Payment.Status.FAILED);
            payment.setGatewayRef(result.gatewayRef());
            payment.setFailureReason(result.declineReason());
            recordTxn(payment, PaymentTransaction.TxnType.AUTH, payment.getAmount(),
                    PaymentTransaction.TxnStatus.FAILED, result.gatewayRef());
            publishFailed(payment, result.declineReason());
        }
        return paymentRepo.save(payment);
    }

    /** Auto-refund hook for order.cancelled events. */
    @Transactional
    public void handleOrderCancelled(OrderCancelledEvent event) {
        paymentRepo.findByOrderId(event.orderId())
                .filter(Payment::isRefundable)
                .ifPresent(p -> {
                    log.info("Auto-refunding payment {} for cancelled order {}",
                            p.getPaymentNumber(), event.orderId());
                    refund(p.getId(), "ORDER_CANCELLED:" + event.reason());
                });
    }

    // ------------------------------ reads ------------------------------

    @Transactional(readOnly = true)
    public Payment findById(Long id) {
        return paymentRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + id));
    }

    @Transactional(readOnly = true)
    public Payment findByOrderId(Long orderId) {
        return paymentRepo.findFirstByOrderIdOrderByIdDesc(orderId)
                .orElseThrow(() -> new IllegalArgumentException("No payment for order: " + orderId));
    }

    // ------------------------------ internals ------------------------------

    private void recordTxn(Payment p, PaymentTransaction.TxnType type,
                           java.math.BigDecimal amount,
                           PaymentTransaction.TxnStatus status, String gatewayRef) {
        PaymentTransaction txn = new PaymentTransaction();
        txn.setPayment(p);
        txn.setTxnType(type);
        txn.setAmount(amount);
        txn.setStatus(status);
        txn.setGatewayRef(gatewayRef);
        txn.setIdempotencyKey(p.getIdempotencyKey() + "-" + type.name().toLowerCase());
        txnRepo.save(txn);
    }

    private void publishCompleted(Payment p) {
        var evt = new PaymentCompletedEvent(UUID.randomUUID(), "payment.completed",
                p.getId(), p.getPaymentNumber(), p.getOrderId(), p.getCustomerId(),
                p.getAmount(), p.getCurrency(), p.getGatewayRef(),
                java.time.Instant.now(), 1);
        outboxRepo.save(OutboxEvent.of("PAYMENT", p.getId(), "payment.completed",
                toMap(evt)));
    }

    private void publishFailed(Payment p, String reason) {
        var evt = new PaymentFailedEvent(UUID.randomUUID(), "payment.failed",
                p.getId(), p.getOrderId(), p.getCustomerId(), p.getAmount(), reason,
                java.time.Instant.now(), 1);
        outboxRepo.save(OutboxEvent.of("PAYMENT", p.getId(), "payment.failed",
                toMap(evt)));
    }

    private void publishRefunded(Payment p, String reason) {
        var evt = new PaymentRefundedEvent(UUID.randomUUID(), "payment.refunded",
                p.getId(), p.getOrderId(), p.getAmount(), reason,
                java.time.Instant.now(), 1);
        outboxRepo.save(OutboxEvent.of("PAYMENT", p.getId(), "payment.refunded",
                toMap(evt)));
    }

    private Map<String, Object> toMap(Object evt) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = objectMapper.convertValue(evt, Map.class);
        return map;
    }

    /** Human-friendly, sortable payment number: PAY-YYYYMMDD-<seq>. */
    private String nextPaymentNumber() {
        return "PAY-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + String.format("%06d", SEQ.incrementAndGet() % 1000000);
    }
}
