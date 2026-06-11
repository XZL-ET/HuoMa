package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.AgentAlert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 员工预警记录 数据访问层。
 *
 * <p>提供对 {@link AgentAlert} 表的 CRUD 操作和自定义查询方法。
 * 继承 Spring Data JPA 的 {@link JpaRepository}，自动获得基础增删改查能力。
 * 主要用于查询员工（客服/服务老师）的异常行为预警、熔断记录，
 * 支持按状态、按时间范围、按严重程度等维度进行检索和统计。</p>
 *
 * @author Bookstore Dev
 * @since 1.10
 */
public interface AgentAlertRepository extends JpaRepository<AgentAlert, Long> {

    /**
     * 按预警状态分页查询，并按创建时间降序排列。
     * <p>
     * 用于预警列表页面的展示，通常管理员按"未处理"(open)状态优先查看。
     * </p>
     *
     * @param status   预警状态（open / resolved / auto_resolved）
     * @param pageable 分页参数（页码、每页大小、排序等）
     * @return 指定状态的预警分页数据，按创建时间从新到旧排序
     */
    Page<AgentAlert> findByStatusOrderByCreatedAtDesc(
            AgentAlert.AlertStatus status, Pageable pageable);

    /**
     * 查询某个员工在指定时间之后产生的特定类型的未处理预警。
     * <p>
     * 用于判断某个员工近期是否已触发过同类预警，避免重复生成相同的预警记录。
     * 例如，检测某个员工在最近 5 分钟内是否已经有一条"响应超时"的未处理预警。
     * </p>
     *
     * @param agentUserid 员工企业微信 userid
     * @param alertType   预警类型标识（如 response_timeout）
     * @param status      预警状态
     * @param after       时间起点，仅返回此时间之后创建的记录
     * @return 匹配条件的预警记录列表
     */
    List<AgentAlert> findByAgentUseridAndAlertTypeAndStatusAndCreatedAtAfter(
            String agentUserid, String alertType, AgentAlert.AlertStatus status,
            LocalDateTime after);

    /**
     * 统计某个员工在指定时间之后产生的特定类型预警的数量。
     * <p>
     * 用于判断某类预警在近期是否频繁出现（如 1 小时内重复告警次数），
     * 辅助进行预警聚合或熔断决策。
     * </p>
     *
     * @param agentUserid 员工企业微信 userid
     * @param alertType   预警类型标识
     * @param after       时间起点，仅统计此时间之后创建的记录
     * @return 符合条件的预警记录总数
     */
    long countByAgentUseridAndAlertTypeAndCreatedAtAfter(
            String agentUserid, String alertType, LocalDateTime after);

    /**
     * 统计指定时间范围内产生的预警总数。
     * <p>
     * 用于运营看板或日报/周报中的预警趋势统计。
     * </p>
     *
     * @param start 统计起始时间（含）
     * @param end   统计结束时间（含）
     * @return 该时间范围内的预警记录总数
     */
    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    /**
     * 按严重程度统计指定时间范围内的预警数量。
     * <p>
     * 用于按严重等级（low / medium / high）分析预警分布，
     * 帮助管理员快速定位需要优先处理的高级别预警。
     * </p>
     *
     * @param severity 预警严重程度（low / medium / high）
     * @param start    统计起始时间（含）
     * @param end      统计结束时间（含）
     * @return 该严重程度在时间范围内的预警记录总数
     */
    long countBySeverityAndCreatedAtBetween(AgentAlert.AlertSeverity severity,
                                            LocalDateTime start, LocalDateTime end);
}
