package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 全局员工池部门准入白名单实体。
 *
 * <p>只有所属企微部门（含子孙部门）命中白名单的员工才允许进入全局员工池。
 * 部门名称作快照保存，用于后台展示与审计；创建时间由 JPA 生命周期自动填充。</p>
 *
 * @author Bookstore Dev
 * @since 2026-11-02
 */
@Entity
@Table(name = "pool_allowed_department")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PoolAllowedDepartment {

    /** 企微部门 ID，主键 */
    @Id
    @Column(name = "dept_id")
    private Long deptId;

    /** 部门名称快照（展示与审计用） */
    @Column(name = "dept_name", nullable = false, length = 100)
    private String deptName;

    /** 创建人 */
    @Column(name = "created_by", length = 100)
    private String createdBy;

    /** 创建时间，不可更新 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 持久化前回调：创建时间为空时填充当前时间。
     */
    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
