package com.commercehub.payment.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * PaymentTransaction — append-only ledger row.
 *
 * Every AUTH/CAPTURE/REFUND/VOID against a payment is a new row; we NEVER
 * update or delete ledger entries. Corrections are compensating entries —
 * this is what makes the ledger auditable and replayable (same principle
 * as double-entry bookkeeping).
 */
@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction {

    public enum TxnType { AUTH, CAPTURE, REFUND, VOID }
    public enum TxnStatus { SUCCESS, FAILED, PENDING }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Enumerated(EnumType.STRING)
    @Column(name = "txn_type", nullable = false, length = 20)
    private TxnType txnType;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TxnStatus status;

    @Column(name = "gateway_ref", length = 80)
    private String gatewayRef;

    @Column(name = "idempotency_key", length = 80)
    private String idempotencyKey;

    @Column(name = "failure_reason", length = 300)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }

    // ---- getters/setters ----
    public Long getId() { return id; }
    public Payment getPayment() { return payment; }
    public void setPayment(Payment p) { payment = p; }
    public TxnType getTxnType() { return txnType; }
    public void setTxnType(TxnType v) { txnType = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { amount = v; }
    public TxnStatus getStatus() { return status; }
    public void setStatus(TxnStatus v) { status = v; }
    public String getGatewayRef() { return gatewayRef; }
    public void setGatewayRef(String v) { gatewayRef = v; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String v) { idempotencyKey = v; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String v) { failureReason = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
