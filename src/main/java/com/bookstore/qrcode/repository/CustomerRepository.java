package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 客户（Customer）数据访问层。
 * <p>
 * 提供对 {@link Customer} 实体的基础 CRUD 操作以及按关键字、学校、接待人、
 * 状态、添加时间等维度的组合搜索能力，同时提供多维度的客户统计查询。
 * Customer 指通过活码扫码添加企业微信好友的最终客户。
 * </p>
 *
 * @author Bookstore Dev Team
 * @since 1.0
 */
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /**
     * 根据企业微信外部联系人 ID（externalUserid）查找客户。
     * <p>
     * externalUserid 是企微通讯录中客户的唯一标识，每个客户全局唯一。
     * 用于同步企微联系人数据时的排重判断。
     * </p>
     *
     * @param externalUserid 企微外部联系人 ID，不可为 null
     * @return 包含匹配客户的 Optional；若未找到则返回 {@link Optional#empty()}
     */
    Optional<Customer> findByExternalUserid(String externalUserid);

    /**
     * 判断指定 externalUserid 是否已存在客户记录。
     * <p>
     * 通常用于从企微回调或批量同步时判断客户是否已入库，避免重复创建。
     * </p>
     *
     * @param externalUserid 企微外部联系人 ID，不可为 null
     * @return 已存在返回 {@code true}，否则返回 {@code false}
     */
    boolean existsByExternalUserid(String externalUserid);

    /**
     * 多条件组合分页搜索客户。
     * <p>
     * JPQL 说明：所有筛选条件均使用 {@code :param IS NULL OR ...} 模式，
     * 当参数为 {@code null} 时自动跳过该条件。这种设计支持前端灵活组合任意筛选维度，
     * 无需为每种组合编写独立查询方法。
     * <ul>
     *   <li><b>keyword</b>（可选）：模糊匹配客户姓名（name）或企微外部联系人 ID
     *       （externalUserid），使用 {@code LIKE %:keyword%} 实现前后模糊</li>
     *   <li><b>schoolId</b>（可选）：精确匹配所属学校 ID，筛选某个学校的全部客户</li>
     *   <li><b>currentAgent</b>（可选）：精确匹配当前接待员工企 ID，
     *       筛选由某个服务老师或后备接待员负责的客户</li>
     *   <li><b>status</b>（可选）：精确匹配客户状态（如 已添加 / 待通过 / 已流失）</li>
     *   <li><b>startTime</b> + <b>endTime</b>（可选）：按客户添加时间范围筛选，
     *       {@code startTime <= addTime <= endTime}；可只传其一实现
     *       "某时间之后"或"某时间之前"的查询</li>
     * </ul>
     * 注意：keyword 使用的是 {@code LIKE %} 模糊匹配，而非全文检索，因此
     * 在海量数据场景下建议配合数据库索引优化性能。
     * </p>
     *
     * @param keyword       搜索关键字（姓名或 externalUserid 模糊匹配），可为 {@code null}
     * @param schoolId      学校 ID 精确筛选，可为 {@code null}
     * @param currentAgent  当前接待员工企 ID 精确筛选，可为 {@code null}
     * @param status        客户状态筛选，可为 {@code null}
     * @param startTime     添加时间范围开始，可为 {@code null}
     * @param endTime       添加时间范围结束，可为 {@code null}
     * @param pageable      分页参数（页码、每页条数、排序等）
     * @return 满足所有非空条件的客户分页数据
     */
    @Query("SELECT c FROM Customer c WHERE "
         + "(:keyword IS NULL OR c.name LIKE %:keyword% OR c.externalUserid LIKE %:keyword%) "
         + "AND (:schoolId IS NULL OR c.schoolId = :schoolId) "
         + "AND (:currentAgent IS NULL OR c.currentAgent = :currentAgent) "
         + "AND (:status IS NULL OR c.status = :status) "
         + "AND (:startTime IS NULL OR c.addTime >= :startTime) "
         + "AND (:endTime IS NULL OR c.addTime <= :endTime)")
    Page<Customer> search(@Param("keyword") String keyword,
                          @Param("schoolId") String schoolId,
                          @Param("currentAgent") String currentAgent,
                          @Param("status") Customer.CustomerStatus status,
                          @Param("startTime") LocalDateTime startTime,
                          @Param("endTime") LocalDateTime endTime,
                          Pageable pageable);

    /**
     * 统计指定时间范围内添加的客户总数。
     * <p>
     * 对应 JPQL 推导：{@code COUNT(c) WHERE c.addTime BETWEEN :start AND :end}，
     * 由 Spring Data JPA 方法命名规则自动生成。
     * 用于管理后台的时间维度报表统计。
     * </p>
     *
     * @param start 时间范围起始（含），不可为 null
     * @param end   时间范围结束（含），不可为 null
     * @return 该时间范围内新增的客户数
     */
    long countByAddTimeBetween(LocalDateTime start, LocalDateTime end);

    /**
     * 统计指定时间范围内、指定状态的客户数量。
     * <p>
     * 在 {@link #countByAddTimeBetween} 的基础上增加状态维度，
     * 用于区分"已添加"与"待通过"等不同阶段的客户数。
     * 例如：统计某月"已添加"状态的客户增长量。
     * </p>
     *
     * @param start  时间范围起始（含），不可为 null
     * @param end    时间范围结束（含），不可为 null
     * @param status 客户状态筛选
     * @return 满足时间范围与状态条件的客户数
     */
    long countByAddTimeBetweenAndStatus(LocalDateTime start, LocalDateTime end,
                                        Customer.CustomerStatus status);

    /**
     * 统计通过指定活码（QrCode）在给定时间范围内添加的客户数量。
     * <p>
     * 用于活码维度的转化分析，衡量每个活码在不同时间段的引流效果。
     * {@code sourceQrId} 对应客户扫码时使用的活码 ID。
     * </p>
     *
     * @param sourceQrId 来源活码 ID（对应 QrCode 的主键），不可为 null
     * @param start      时间范围起始（含），不可为 null
     * @param end        时间范围结束（含），不可为 null
     * @return 该活码在指定时间范围内添加的客户数
     */
    long countBySourceQrIdAndAddTimeBetween(Long sourceQrId, LocalDateTime start, LocalDateTime end);

    /**
     * 分页查询需要数据修复的客户（名称缺失、unionid 缺失或头像缺失）。
     * 仅返回需要修复的记录，避免全表扫描。
     */
    @Query("SELECT c FROM Customer c WHERE c.name = '未知' OR c.unionid IS NULL OR c.avatar IS NULL")
    Page<Customer> findNeedingRepair(Pageable pageable);

    /**
     * 员工排行榜 — 按添加客户数分组排名 Top N。
     * 返回 {@code Object[]} 数组，[0]=员工userid（String），[1]=客户数（Long）。
     */
    @Query("SELECT c.addedAgent, COUNT(c) FROM Customer c " +
           "WHERE c.addTime >= :start AND c.addTime < :end " +
           "AND c.addedAgent IS NOT NULL " +
           "GROUP BY c.addedAgent ORDER BY COUNT(c) DESC")
    List<Object[]> findTopAdders(@Param("start") LocalDateTime start,
                                  @Param("end") LocalDateTime end,
                                  Pageable pageable);

    /**
     * 活码排行榜 — 按添加客户数分组排名 Top N。
     * 返回 {@code Object[]} 数组，[0]=活码ID（Long），[1]=客户数（Long）。
     */
    @Query("SELECT c.sourceQrId, COUNT(c) FROM Customer c " +
           "WHERE c.addTime >= :start AND c.addTime < :end " +
           "AND c.sourceQrId IS NOT NULL " +
           "GROUP BY c.sourceQrId ORDER BY COUNT(c) DESC")
    List<Object[]> findTopQrCodes(@Param("start") LocalDateTime start,
                                   @Param("end") LocalDateTime end,
                                   Pageable pageable);

    /**
     * 统计指定时间范围内有新增客户的去重活码数。
     * 用于活码利用漏斗中的"今日有新增"环节。
     */
    @Query("SELECT COUNT(DISTINCT c.sourceQrId) FROM Customer c " +
           "WHERE c.addTime >= :start AND c.addTime < :end " +
           "AND c.sourceQrId IS NOT NULL")
    long countDistinctSourceQrByAddTimeBetween(@Param("start") LocalDateTime start,
                                               @Param("end") LocalDateTime end);
}
