package com.commercehub.payment.controller;

import com.commercehub.payment.dto.PaymentDtos;
import com.commercehub.payment.entity.Payment;
import com.commercehub.payment.entity.PaymentTransaction;
import com.commercehub.payment.repository.PaymentTransactionRepository;
import com.commercehub.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * /api/v1/payments — query + manual operations.
 * The saga path (inventory.reserved → charge) is event-driven and needs
 * no REST endpoint; these serve status checks, retries, and refunds.
 */
@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Payment status, retries, refunds")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentTransactionRepository txnRepo;

    public PaymentController(PaymentService paymentService,
                             PaymentTransactionRepository txnRepo) {
        this.paymentService = paymentService;
        this.txnRepo = txnRepo;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get payment by id")
    public PaymentDtos.PaymentResponse get(@PathVariable Long id) {
        return toResponse(paymentService.findById(id));
    }

    @GetMapping("/order/{orderId}")
    @Operation(summary = "Get payment for an order")
    public PaymentDtos.PaymentResponse byOrder(@PathVariable Long orderId) {
        return toResponse(paymentService.findByOrderId(orderId));
    }

    @GetMapping("/{id}/transactions")
    @Operation(summary = "Transaction ledger for a payment")
    @Transactional(readOnly = true)
    public List<PaymentDtos.TransactionResponse> transactions(@PathVariable Long id) {
        return txnRepo.findByPaymentIdOrderByCreatedAtAsc(id).stream()
                .map(this::toTxnResponse).toList();
    }

    /** Manual retry of a failed payment — new attempt, new idempotency key. */
    @PostMapping("/{id}/retry")
    @Operation(summary = "Retry a failed payment (ADMIN)")
    public ResponseEntity<PaymentDtos.PaymentResponse> retry(
            @PathVariable Long id,
            @RequestHeader("X-User-Roles") String roles) {
        requireAdmin(roles);
        Payment p = paymentService.findById(id);
        if (p.getStatus() != Payment.Status.FAILED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only FAILED payments can be retried");
        }
        return ResponseEntity.ok(toResponse(paymentService.retryFailed(id)));
    }

    @PostMapping("/{id}/refund")
    @Operation(summary = "Refund a captured payment (ADMIN)")
    public PaymentDtos.PaymentResponse refund(
            @PathVariable Long id,
            @RequestParam(defaultValue = "MANUAL_REFUND") String reason,
            @RequestHeader("X-User-Roles") String roles) {
        requireAdmin(roles);
        return toResponse(paymentService.refund(id, reason));
    }

    private void requireAdmin(String roles) {
        if (roles == null || !List.of(roles.split(",")).contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN role required");
        }
    }

    private PaymentDtos.PaymentResponse toResponse(Payment p) {
        return new PaymentDtos.PaymentResponse(p.getId(), p.getPaymentNumber(),
                p.getOrderId(), p.getCustomerId(), p.getAmount(), p.getCurrency(),
                p.getStatus().name(), p.getGatewayRef(), p.getFailureReason(),
                p.getCreatedAt(), p.getCompletedAt());
    }

    private PaymentDtos.TransactionResponse toTxnResponse(PaymentTransaction t) {
        return new PaymentDtos.TransactionResponse(t.getId(), t.getTxnType().name(),
                t.getAmount(), t.getStatus().name(), t.getGatewayRef(), t.getCreatedAt());
    }
}
