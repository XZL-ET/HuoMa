package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日运营数据汇总实体。
 * <p>
 * 按日期唯一存储（每天一条记录），由定时任务每日凌晨自动生成前一天的运营统计数据，
 * 涵盖扫码、添加好友、转接、轮换、告警以及二维码/客服健康状态等核心指标，
 * 为运营看板与报表提供数据支撑。
 * </p>
 *
 * @author Bookstore Dev
 * @since 1.0
 */
@Entity
@Table(name = "daily_report")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyReport {

    /** 主键 ID，自增生成 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 统计日期（格式 yyyy-MM-dd），唯一且不可为空，每条记录对应一天的数据汇总 */
    @Column(nullable = false, unique = true)
    private LocalDate date;

    /** 日总扫码次数：客户扫描活码的总 PV，反映引流入口的曝光量 */
    @Builder.Default
    private Integer totalScan = 0;

    /** 日成功添加好友数：客户通过扫描活码成功添加客服好友的总人数 */
    @Builder.Default
    private Integer totalAdd = 0;

    /** 日添加好友失败数：客户扫码后因风控、账号异常等原因添加失败的总次数 */
    @Column(name = "total_add_fail")
    @Builder.Default
    private Integer totalAddFail = 0;

    /** 日转接总次数：系统根据排队/负载策略将客户转交给其他客服的总次数 */
    @Builder.Default
    private Integer totalTransfer = 0;

    /** 日转接成功数：转接后客户成功添加目标客服好友的总次数 */
    @Column(name = "total_transfer_ok")
    @Builder.Default
    private Integer totalTransferOk = 0;

    /** 日轮换次数：活码在其关联的多个客服之间按策略轮换分配的累计次数 */
    @Builder.Default
    private Integer totalRotate = 0;

    /** 日告警次数：触发风控规则、客服异常或其他监控告警的累计次数 */
    @Builder.Default
    private Integer totalAlert = 0;

    /** 日活跃二维码数：当日至少被扫码一次或仍有添加容量的活码数量 */
    @Builder.Default
    private Integer activeQr = 0;

    /** 日已满二维码数：当日达到添加上限、无法继续添加客户的活码数量 */
    @Builder.Default
    private Integer fullQr = 0;

    /** 日被限制客服数：当日因频繁操作或触发微信风控而被限制添加好友的客服账号数 */
    @Builder.Default
    private Integer blockedAgent = 0;

    /** 日熔断客服数：当日达到日接上限后触发熔断保护、暂停接客的客服账号数 */
    @Builder.Default
    private Integer meltedAgent = 0;

    /**
     * 每日详细运营数据的 JSON 字符串。
     * <p>
     * 结构示例（按需扩展）：
     * <pre>
     * {
     *   "scanHourly": [0,0,5,12,...],         // 每小时扫码分布（24 个整数）
     *   "addHourly":  [0,0,3,8,...],           // 每小时添加成功分布
     *   "agentStats": [                         // 各客服明细
     *     { "agentId":1, "add":25, "fail":2, "transferIn":5, "transferOut":3, "blocked":false, "melted":true }
     *   ],
     *   "topQr": [                             // 扫码 Top N 的活码
     *     { "qrId":10, "name":"门店1", "scan":120, "add":30 }
     *   ]
     * }
     * </pre>
     * 上层消费方应使用 JSON 解析库（如 Jackson / Gson）读取此字段。
     * </p>
     */
    @Column(name = "detail_json", columnDefinition = "JSON")
    private String detailJson;

    /** 记录创建时间（即定时任务生成该条统计的时间），写入后不可更新 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * JPA 持久化前回调：在插入前自动填充 {@link #createdAt} 为当前数据库时间。
     * 注意：使用 Hibernate 的 {@code @PrePersist}，仅在首次 persist 时生效，
     * 后续更新不会修改该字段。
     */
    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
