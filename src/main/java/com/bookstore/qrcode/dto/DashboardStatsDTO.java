package com.bookstore.qrcode.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 看板统计指标 DTO。
 * <p>
 * 聚合活码、员工、客户等多个维度的核心统计指标，
 * 由 {@link com.bookstore.qrcode.service.DashboardService#getDashboardStats()} 一次性计算，
 * 配合 {@code @Cacheable("dashboard-stats")} 缓存 60s，避免每次页面加载触发 15+ 次独立 DB 查询。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStatsDTO {

    /** 活码总数 */
    private long totalQrCodes;

    /** 正常状态的活码数 */
    private long activeQrCodes;

    /** 已满的活码数 */
    private long fullQrCodes;

    /** 无可用客服的活码数 */
    private long noAgentQrCodes;

    /** 员工总数 */
    private long totalAgents;

    /** 后备池待命员工数 */
    private long standbyPoolCount;

    /** 已停用的员工数 */
    private long blockedAgentCount;

    /** 已熔断的员工数 */
    private long meltedAgentCount;

    /** 今日新增客户数 */
    private long todayAdds;

    /** 今日删除客户数 */
    private long todayDeletes;

    /** 今日转接次数 */
    private long todayTransfers;

    /** 今日预警次数 */
    private long todayAlerts;

    /** 今日轮换次数 */
    private long todayRotates;
}
