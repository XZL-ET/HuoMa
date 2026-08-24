package com.bookstore.qrcode.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 全量员工视图行 DTO。
 *
 * <p>聚合 Employee + Agent + GlobalAgentPool 三表数据，
 * 用于员工管理页「全量视图」的表格展示。
 * 模板通过 Thymeleaf 条件渲染 badges，无需在 Java 层做标签映射。</p>
 *
 * @author Bookstore Dev
 * @since 2.7.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AllEmployeeRow {

    /** 企微 userid */
    private String userid;

    /** 显示名称（Employee.name → Agent.name → userid 兜底） */
    private String name;

    /** Employee.active — 是否在职 */
    private Boolean active;

    /** Employee.wechatStatus — 企微侧状态: 1=已激活 2=禁用 4=未激活 5=已离职 */
    private Integer wechatStatus;

    /** Agent.overallStatus.name() — 综合状态: normal/warning/blocked/melted */
    private String agentOverallStatus;

    /** Agent.role.name() — 角色: receptionist/service/dual */
    private String agentRole;

    /** GlobalAgentPool.status.name() — 池状态: standby/full/blocked，null=未入池 */
    private String poolStatus;

    /** Pool.dailyCurrent — 今日已接待 */
    private Integer dailyCurrent;

    /** Pool.dailyMax — 日上限 */
    private Integer dailyMax;

    /** Pool.sortOrder — 池内优先级 */
    private Integer sortOrder;

    /** 异常标签: 未实名/未加入组织/已停用/已熔断/预警/已离职/未激活/已禁用，null=正常 */
    private String anomalyLabel;

    /** 异常标签 CSS class: bg-danger / bg-warning text-dark */
    private String anomalyClass;

    /** 状态变更原因（JSON 格式），用于确认恢复弹窗展示 */
    private String statusReason;

    /** 所在活码名称列表 */
    @Builder.Default
    private List<String> qrCodeNames = List.of();
}
