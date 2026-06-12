package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 企微内部员工实体。
 *
 * <p>从企微 API {@code /user/simplelist} 定时同步到本地 DB，
 * 用于活码创建页面的员工选择器等场景，避免每次页面加载都调用企微 API。</p>
 *
 * @author Bookstore Dev
 * @since 1.4.0
 */
@Entity
@Table(name = "employee")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Employee {

    /** 主键 ID，自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 企微用户 ID，同一租户下唯一 */
    @Column(nullable = false, unique = true, length = 100)
    private String userid;

    /** 员工姓名（企微通讯录中的显示名称） */
    @Column(nullable = false, length = 100)
    private String name;

    /** 所属部门 ID 列表（JSON 数组字符串，如 "[1,2]"） */
    @Column(length = 500)
    private String department;

    /** 是否在职（离职员工标记为 false，不删除记录以保留历史关联） */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    /** 最近一次同步时间 */
    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime lastSyncTime = LocalDateTime.now();
}
