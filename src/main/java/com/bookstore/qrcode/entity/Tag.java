package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 客户标签实体。
 *
 * <p>标签是客户画像体系的基础组成部分，用于对客户进行分组和标记，支持三种来源：
 * <ul>
 *   <li><b>system（系统自动打标）</b>：由系统在特定业务节点自动为客户打上标签，例如客户通过活码添加好友时根据扫码来源自动打标；</li>
 *   <li><b>form（表单自动打标）</b>：客户在填写表单时，根据所选选项自动关联标签；</li>
 *   <li><b>manual（手动标签）</b>：运营人员在后台手动创建，或通过企微标签同步导入的标签。</li>
 * </ul>
 *
 * <p>标签支持父子层级结构（标签组 > 子标签），通过 {@link #parentId} 字段实现层级关联。
 * 同时通过 {@link #wecomTagId} 与企业微信（企微）平台进行双向标签同步，确保线上线下标签体系一致。
 *
 * <p>关联服务与实体：{@link com.bookstore.qrcode.service.TagService TagService}（标签业务逻辑）、
 * {@link com.bookstore.qrcode.entity.CustomerTag CustomerTag}（客户-标签关联关系）。
 *
 * @author Bookstore Dev
 * @since 1.0
 */
@Entity
@Table(name = "tag")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tag {
    /** 主键，数据库自增生成。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 标签名称，长度不超过 100 个字符。支持中文、英文及特殊字符。与 groupKeyword 构成复合唯一约束。 */
    @Column(nullable = false, length = 100)
    private String name;

    /** 标签来源类型：system（系统自动打标）、form（表单自动打标）、manual（手动创建/企微同步）。默认值为 manual。 */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private TagType type = TagType.manual;

    /** 父级标签 ID，支持层级结构。为 null 时表示顶层标签（标签组），非 null 时表示子标签。 */
    @Column(name = "parent_id")
    private Long parentId;

    /** 企业微信（企微）平台中的标签 ID，用于双向同步。为 null 或空串时表示未关联企微标签。 */
    @Column(name = "wecom_tag_id", length = 50)
    private String wecomTagId;

    /** 企微标签组关键词，与 name 构成复合唯一约束。如"市州"、"县区"、"学校-兰州市"。默认空串兼容旧数据。 */
    @Column(name = "group_keyword", nullable = false, length = 100)
    @Builder.Default
    private String groupKeyword = "";

    /** 记录创建时间，由 {@link #prePersist()} 自动填充，不可更新。 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 实体持久化前的生命周期回调方法。
     *
     * <p>在 JPA 执行 {@code INSERT} 操作之前自动调用，为 {@link #createdAt} 字段设置当前系统时间，
     * 确保创建时间由数据库/应用层自动填充，无需业务代码手动赋值。
     */
    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }

    /**
     * 标签来源类型枚举。
     *
     * <p>用于标识标签的创建来源和用途：
     * <ul>
     *   <li><b>system</b> — 系统自动打标：由系统在特定业务节点自动为客户打上标签，如通过活码添加好友时自动打标；</li>
     *   <li><b>form</b> — 表单自动打标：客户在填表时根据所选选项自动关联标签，用于表单获客场景；</li>
     *   <li><b>manual</b> — 手动标签：运营人员在后台手工创建，或通过企微标签同步功能从企业微信导入。</li>
     * </ul>
     */
    public enum TagType {
        system, form, manual
    }
}
