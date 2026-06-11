package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 客户与标签的多对多关联实体。
 *
 * <p>一个客户可以拥有多个标签，一个标签也可以打在多个客户身上（多对多关系）。
 * 该实体用于记录客户与标签之间的绑定关系，同时追踪打标的来源（system/form/manual）及打标时间。
 * 通过数据库层唯一约束（customer_id + tag_id）确保同一客户不会被重复打上同一个标签。
 *
 * @author Bookstore Dev
 * @since 1.0
 */
@Entity
@Table(name = "customer_tag", uniqueConstraints = @UniqueConstraint(columnNames = {"customer_id", "tag_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerTag {

    /** 主键 ID，自增生成 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 客户 ID，关联 customer 表 */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** 标签 ID，关联 tag 表 */
    @Column(name = "tag_id", nullable = false)
    private Long tagId;

    /**
     * 打标来源。
     *
     * <p>追踪该标签是通过哪种方式打在客户身上的，默认为 system，即系统自动打标。
     */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private TagSource source = TagSource.system;

    /** 打标时间，记录标签与客户绑定的时间点，插入后不可更新 */
    @Column(name = "tagged_at", updatable = false)
    private LocalDateTime taggedAt;

    /** 持久化前自动设置打标时间为当前时间 */
    @PrePersist
    void prePersist() {
        taggedAt = LocalDateTime.now();
    }

    /**
     * 打标来源枚举。
     *
     * <p>标识客户标签是通过何种方式绑定的，用于后续数据统计和来源追溯。
     */
    public enum TagSource {

        /** 系统自动打标：根据业务规则（如扫码活码）由系统自动为客户添加标签 */
        system,

        /** 客户填表打标：客户通过填写表单（如预约表单、报名表）时选择的标签 */
        form,

        /** 手动打标：运营人员在后台手动为客户添加标签 */
        manual
    }
}
