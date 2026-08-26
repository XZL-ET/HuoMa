package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.CustomerTransfer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 客户转移数据访问层。
 * <p>
 * 提供对 customer_transfer（客户转移记录）表的 CRUD 操作和自定义查询。
 * 用于记录客户在接待员之间的转移历史，支持重试机制和统计报表查询。
 * </p>
 *
 * @author Bookstore Dev
 * @since 1.0
 */
public interface CustomerTransferRepository extends JpaRepository<CustomerTransfer, Long> {

    /**
     * 根据客户 ID 查询该客户的所有转移记录。
     *
     * @param customerId 客户 ID
     * @return 该客户的转移记录列表
     */
    List<CustomerTransfer> findByCustomerId(Long customerId);

    /**
     * 根据活码 ID 分页查询转移记录，按转移时间倒序排列。
     *
     * @param qrCodeId 活码 ID
     * @param pageable 分页参数
     * @return 转移记录分页结果
     */
    Page<CustomerTransfer> findByQrCodeIdOrderByTransferTimeDesc(Long qrCodeId, Pageable pageable);

    /**
     * 根据转移状态查询所有符合条件的转移记录。
     *
     * @param status 转移状态（如 PENDING、SUCCESS、FAILED）
     * @return 指定状态的转移记录列表
     */
    List<CustomerTransfer> findByStatus(CustomerTransfer.TransferStatus status);

    /**
     * 查询指定状态下 API 重试次数未超过上限的转移记录。
     * <p>
     * 用于 retryFailedTransfers：扫描 api_failed 状态、retryCount &lt; 3 的记录。
     * </p>
     *
     * @param status     转移状态
     * @param maxRetries 最大允许重试次数
     * @return 可继续重试的转移记录列表
     */
    List<CustomerTransfer> findByStatusAndRetryCountLessThan(
            CustomerTransfer.TransferStatus status, int maxRetries);

    /**
     * 查询 api_failed 状态、重试次数未达上限、且退避已到期（nextRetryAt 为空或已过）的记录。
     * <p>
     * 用于 retryFailedTransfers 的退避重试扫描。nextRetryAt 为空表示历史遗留记录
     * 或首次失败（尚未设置退避时间），视为立即可重试。
     * </p>
     *
     * @param status     转移状态（api_failed）
     * @param maxRetries 最大允许重试次数
     * @param now        当前时间，用于判断退避是否到期
     * @return 可立即重试的转移记录列表
     */
    @Query("SELECT t FROM CustomerTransfer t "
        + "WHERE t.status = :status AND t.retryCount < :maxRetries "
        + "AND (t.nextRetryAt IS NULL OR t.nextRetryAt <= :now)")
    List<CustomerTransfer> findDueForRetry(
            @Param("status") CustomerTransfer.TransferStatus status,
            @Param("maxRetries") int maxRetries,
            @Param("now") LocalDateTime now);

    /**
     * 查询指定状态下轮询次数未超过上限的转移记录。
     * <p>
     * 用于 trackResults：扫描 pending_confirm 状态、pollCount &lt; 48 的记录。
     * </p>
     *
     * @param status     转移状态
     * @param maxPolls   最大允许轮询次数
     * @return 可继续轮询的转移记录列表
     */
    List<CustomerTransfer> findByStatusAndPollCountLessThan(
            CustomerTransfer.TransferStatus status, int maxPolls);

    /**
     * 统计指定时间范围内的客户转移总次数。
     * <p>
     * 用于日报/报表中计算某段时间内的转移总量。
     * </p>
     *
     * @param start 起始时间
     * @param end   结束时间
     * @return 转移总次数
     */
    long countByTransferTimeBetween(LocalDateTime start, LocalDateTime end);

    /**
     * 统计指定时间范围内特定状态的转移次数。
     * <p>
     * 用于日报/报表中按状态（如成功/失败）分类统计转移量。
     * </p>
     *
     * @param status 转移状态
     * @param start  起始时间
     * @param end    结束时间
     * @return 指定状态的转移次数
     */
    long countByStatusAndTransferTimeBetween(CustomerTransfer.TransferStatus status,
                                             LocalDateTime start, LocalDateTime end);

    /**
     * 统计指定时间范围内特定状态的转移次数（参数顺序变体）。
     * <p>
     * 与 {@link #countByStatusAndTransferTimeBetween} 功能相同，
     * JPA 方法名解析会自动推导查询，此处提供参数顺序不同的重载以便调用方灵活选择。
     * </p>
     *
     * @param start  起始时间
     * @param end    结束时间
     * @param status 转移状态
     * @return 指定状态的转移次数
     */
    long countByTransferTimeBetweenAndStatus(
            LocalDateTime start, LocalDateTime end, CustomerTransfer.TransferStatus status);

    /**
     * 判断指定客户是否存在处于给定状态集合中的转移记录。
     * <p>
     * 用于在职继承去重：在发起新转移前检查该客户是否已有
     * pending_confirm 或 confirmed 的记录，避免重复转移。
     * </p>
     *
     * @param customerId 客户 ID
     * @param statuses   状态集合
     * @return true 如果存在匹配记录
     */
    boolean existsByCustomerIdAndStatusIn(Long customerId, List<CustomerTransfer.TransferStatus> statuses);

    /**
     * 统计指定活码下所有转移记录总数。
     */
    long countByQrCodeId(Long qrCodeId);

    /**
     * 统计指定活码下特定状态的转移记录数。
     */
    long countByQrCodeIdAndStatus(Long qrCodeId, CustomerTransfer.TransferStatus status);

    /**
     * 统计指定目标员工在指定状态下的转移记录总数。
     * <p>
     * 用于检测某服务老师/双角色的转移失败是否已积累到告警阈值。
     * 跨批次累积统计，而非仅看单次重试批量。
     * </p>
     *
     * @param toUserid 目标员工企微 userid
     * @param status   转移状态（通常为 retry_limit）
     * @return 该目标在指定状态下的转移记录总数
     */
    long countByToUseridAndStatus(String toUserid, CustomerTransfer.TransferStatus status);

    /**
     * 查找 retry_limit 积累达到告警阈值的服务老师/双角色及其记录数。
     * <p>
     * 一次 JOIN 查询覆盖所有到达 {@code retry_limit} 的路径
     * （{@code initiate()} 终端错误 + {@code api_failed} 重试耗尽）。
     * 使用子查询而非 JOIN 避免一个老师挂多个活码时笛卡尔积导致 COUNT 虚高。
     * </p>
     *
     * <p><b>三层防护：</b>
     * <ol>
     *   <li>排除终端/不可操作类型（84061/84073/84096/84100/45035/60111/40003/轮询耗尽/已有进行中转移），只统计需人工介入的服务老师侧问题</li>
     *   <li>7 天时间窗口，防止历史记录导致永久重复告警</li>
     *   <li>HAVING COUNT &ge; 3，在数据库侧完成阈值过滤</li>
     * </ol>
     * </p>
     *
     * @param since 统计起始时间（transferTime &ge; since），调用方传入 7 天前
     * @return 每行 [toUserid, count]，仅包含 count &ge; 3 的老师
     */
    @Query("SELECT t.toUserid, COUNT(t) FROM CustomerTransfer t "
        + "WHERE t.status = 'retry_limit' "
        + "AND t.transferTime >= :since "
        + "AND t.toUserid IN ("
        + "  SELECT a.agentUserid FROM QrAgent a "
        + "  WHERE a.role IN ('service', 'dual') "
        + "  AND a.status <> 'removed') "
        + "AND (t.failReason IS NULL OR t.failReason = '' "
        + "  OR (t.failReason NOT LIKE '%errcode=84061%' "
        + "    AND t.failReason NOT LIKE '%errcode=84073%' "
        + "    AND t.failReason NOT LIKE '%errcode=84096%' "
        + "    AND t.failReason NOT LIKE '%errcode=84100%' "
        + "    AND t.failReason NOT LIKE '%errcode=45035%' "
        + "    AND t.failReason NOT LIKE '%轮询次数耗尽%' "
        + "    AND t.failReason NOT LIKE '%已有进行中的转移%' "
        + "    AND t.failReason NOT LIKE '%errcode=60111%' "
        + "    AND t.failReason NOT LIKE '%errcode=40003%')) "
        + "GROUP BY t.toUserid "
        + "HAVING COUNT(t) >= 3")
    List<Object[]> findRetryLimitTeachers(@Param("since") LocalDateTime since);

    /**
     * 查询指定状态下 API 重试次数已达上限的转移记录。
     * <p>
     * 用于将 retryCount 耗尽但仍处于 api_failed 的记录标记为 retry_limit。
     * 注意：trackResults 的轮询耗尽使用 {@link #findByStatusAndPollCountGreaterThanEqual}。
     * </p>
     */
    List<CustomerTransfer> findByStatusAndRetryCountGreaterThanEqual(
            CustomerTransfer.TransferStatus status, int minRetries);

    /**
     * 查询已确认且有表单提交的转移记录，用于修复损坏的备注。
     */
    @Query("SELECT DISTINCT ct FROM CustomerTransfer ct "
        + "JOIN FormSubmission fs ON fs.customerId = ct.customerId "
        + "WHERE ct.status = 'confirmed' AND ct.greetingSent = true "
        + "AND ct.noteSent = true")
    List<CustomerTransfer> findConfirmedWithFormSubmission();

    /**
     * 查询指定状态下轮询次数已达上限的转移记录。
     * <p>
     * 用于 trackResults 安全网：将 pollCount ≥ 48 但仍处于 pending_confirm
     * 的记录标记为 retry_limit。
     * </p>
     */
    List<CustomerTransfer> findByStatusAndPollCountGreaterThanEqual(
            CustomerTransfer.TransferStatus status, int minPolls);

    /**
     * 查询已确认但欢迎语未发送的转移记录，限定确认时间窗口以防止无限重试。
     * <p>
     * 用于定时补偿发送失败的交接欢迎语。仅重试 24 小时内的记录，
     * 超过 24 小时的认为发送窗口已过，不再重试。
     * </p>
     *
     * @param status       转移状态（通常为 confirmed）
     * @param greetingSent 欢迎语是否已发送（false = 未发送）
     * @param confirmSince 确认时间下限（含）
     * @return 符合条件且未超期的记录列表
     */
    List<CustomerTransfer> findByStatusAndGreetingSentAndConfirmTimeAfter(
        CustomerTransfer.TransferStatus status, boolean greetingSent,
        java.time.LocalDateTime confirmSince);

    /**
     * 查询状态为 terminal（timeout/rejected/retry_limit）且更新时间超过指定天数的记录。
     * <p>
     * 用于定期清理已终结的旧转移记录，避免表无限膨胀。
     * </p>
     */
    @Query("SELECT t FROM CustomerTransfer t WHERE t.status IN ('timeout', 'rejected', 'retry_limit') AND t.updatedAt < :cutoff")
    List<CustomerTransfer> findTerminalOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 批量删除指定 ID 列表中的记录（单条 DELETE 语句，避免 N+1）。
     */
    @Modifying
    @Query("DELETE FROM CustomerTransfer t WHERE t.id IN :ids")
    void deleteAllByIdIn(@Param("ids") List<Long> ids);

    /**
     * 判断指定客户是否存在最近 N 天内的 terminal 转移记录
     * （timeout / rejected / retry_limit），用于冷却期检查。
     */
    @Query("SELECT COUNT(t) > 0 FROM CustomerTransfer t WHERE t.customerId = :customerId AND t.status IN ('timeout', 'rejected', 'retry_limit') AND t.updatedAt >= :since")
    boolean existsRecentTerminalByCustomerId(@Param("customerId") Long customerId, @Param("since") LocalDateTime since);

    /**
     * 按活码汇总指定加人时间范围内的转移结果（转接记录列表页一级视图）。
     * <p>
     * 以 {@link com.bookstore.qrcode.entity.Customer#addTime} 驱动时间筛选：
     * 找出该时间段内通过活码新增的客户，并按其转移记录状态统计。
     * 口径：成功 = confirmed；失败 = rejected/timeout/api_failed/retry_limit 之和；
     * 进行中 = pending_confirm。
     * </p>
     *
     * @param start 加人时间下限（含）
     * @param end   加人时间上限（含）
     * @return 每行格式 [qrCodeId, schoolName, newCustomerCount, confirmedCount, failedCount, pendingCount]
     */
    @Query("SELECT q.id, q.schoolName, "
        + "COUNT(DISTINCT c.id), "
        + "COALESCE(SUM(CASE WHEN t.status = 'confirmed' THEN 1 ELSE 0 END), 0), "
        + "COALESCE(SUM(CASE WHEN t.status IN ('rejected','timeout','api_failed','retry_limit') THEN 1 ELSE 0 END), 0), "
        + "COALESCE(SUM(CASE WHEN t.status = 'pending_confirm' THEN 1 ELSE 0 END), 0) "
        + "FROM QrCode q "
        + "JOIN Customer c ON c.sourceQrId = q.id "
        + "LEFT JOIN CustomerTransfer t ON t.customerId = c.id "
        + "WHERE c.addTime >= :start AND c.addTime <= :end "
        + "GROUP BY q.id, q.schoolName "
        + "ORDER BY COUNT(DISTINCT c.id) DESC")
    List<Object[]> summarizeTransfersByQrCode(@Param("start") LocalDateTime start,
                                              @Param("end") LocalDateTime end);

    /**
     * 查询指定活码下、加人时间落在指定区间内的客户的转移记录（转接记录详情页）。
     * <p>
     * 与 {@link #summarizeTransfersByQrCode} 口径一致：时间筛选基于
     * {@code Customer.addTime}，通过 customer 与 transfer 的显式 JOIN 关联。
     * </p>
     *
     * @param qrCodeId 活码 ID
     * @param start    加人时间下限（含）
     * @param end      加人时间上限（含）
     * @return 转移记录列表，按转移时间倒序
     */
    @Query("SELECT t FROM CustomerTransfer t "
        + "JOIN Customer c ON c.id = t.customerId "
        + "WHERE c.sourceQrId = :qrCodeId "
        + "AND c.addTime >= :start AND c.addTime <= :end "
        + "ORDER BY t.transferTime DESC")
    List<CustomerTransfer> findByQrCodeAndCustomerAddTimeBetween(
        @Param("qrCodeId") Long qrCodeId,
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end);

    /**
     * 按区县负责人汇总指定转移时间范围内的转接结果（每日转接对账推送）。
     * <p>
     * 关联链：{@code CustomerTransfer → Customer(sourceQrId) → QrCode(regionCity/regionDistrict)
     * → DistrictManager(managerUserid)}。时间筛选基于 {@code transferTime}（转移发起时间），
     * 用于"昨日对账"：统计昨日发起的转接当天截至的各类状态。
     * </p>
     *
     * @param start 转移时间下限（含）
     * @param end   转移时间上限（不含）
     * @return 每行格式 [managerUserid, managerName, total, confirmed, rejected, timeout, apiFailed, retryLimit, pending]
     */
    @Query("SELECT dm.managerUserid, dm.managerName, "
        + "COUNT(t), "
        + "COALESCE(SUM(CASE WHEN t.status = 'confirmed' THEN 1 ELSE 0 END), 0), "
        + "COALESCE(SUM(CASE WHEN t.status = 'rejected' THEN 1 ELSE 0 END), 0), "
        + "COALESCE(SUM(CASE WHEN t.status = 'timeout' THEN 1 ELSE 0 END), 0), "
        + "COALESCE(SUM(CASE WHEN t.status = 'api_failed' THEN 1 ELSE 0 END), 0), "
        + "COALESCE(SUM(CASE WHEN t.status = 'retry_limit' THEN 1 ELSE 0 END), 0), "
        + "COALESCE(SUM(CASE WHEN t.status = 'pending_confirm' THEN 1 ELSE 0 END), 0) "
        + "FROM CustomerTransfer t "
        + "JOIN Customer c ON c.id = t.customerId "
        + "JOIN QrCode q ON q.id = c.sourceQrId "
        + "JOIN DistrictManager dm ON dm.regionCity = q.regionCity AND dm.regionDistrict = q.regionDistrict "
        + "WHERE t.transferTime >= :start AND t.transferTime < :end "
        + "GROUP BY dm.managerUserid, dm.managerName")
    List<Object[]> summarizeTransfersByManager(@Param("start") LocalDateTime start,
                                               @Param("end") LocalDateTime end);
}
