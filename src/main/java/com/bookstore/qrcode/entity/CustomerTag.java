package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "customer_tag", uniqueConstraints = @UniqueConstraint(columnNames = {"customer_id", "tag_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerTag {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "tag_id", nullable = false)
    private Long tagId;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private TagSource source = TagSource.system;

    @Column(name = "tagged_at", updatable = false)
    private LocalDateTime taggedAt;

    @PrePersist
    void prePersist() { taggedAt = LocalDateTime.now(); }

    public enum TagSource { system, form, manual }
}
