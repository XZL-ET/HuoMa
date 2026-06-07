package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "customer_transfer")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerTransfer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "from_userid", nullable = false, length = 100)
    private String fromUserid;

    @Column(name = "to_userid", nullable = false, length = 100)
    private String toUserid;

    @Column(name = "qr_code_id")
    private Long qrCodeId;

    @Column(name = "transfer_time")
    private LocalDateTime transferTime;

    @Column(name = "confirm_time")
    private LocalDateTime confirmTime;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private TransferStatus status = TransferStatus.pending_confirm;

    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "fail_reason", length = 500)
    private String failReason;

    @Column(name = "form_filled_at_transfer")
    private Boolean formFilledAtTransfer;

    @Column(name = "note_sent")
    @Builder.Default
    private Boolean noteSent = false;

    @Column(name = "greeting_sent")
    @Builder.Default
    private Boolean greetingSent = false;

    @Column(name = "greeting_type", length = 20)
    @Enumerated(EnumType.STRING)
    private GreetingType greetingType;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = LocalDateTime.now(); }

    public enum TransferStatus { pending_confirm, confirmed, rejected, timeout, api_failed, retry_limit }
    public enum GreetingType { filled, unfilled }
}
