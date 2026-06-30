package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.CustomerTransfer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
     * 查询指定状态下重试次数未超过上限的转移记录。
     * <p>
     * 用于定时任务扫描「待重试」状态的转移，排除已达到最大重试次数的记录，
     * 避免对已失败的转移无限重试。
     * </p>
     *
     * @param status     转移状态
     * @param maxRetries 最大允许重试次数
     * @return 可继续重试的转移记录列表
     */
    List<CustomerTransfer> findByStatusAndRetryCountLessThan(
            CustomerTransfer.TransferStatus status, int maxRetries);

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
     * 查询指定状态下重试次数已达上限的转移记录。
     * <p>
     * 用于将重试耗尽但仍处于 pending_confirm 的记录标记为 retry_limit。
     * </p>
     */
    List<CustomerTransfer> findByStatusAndRetryCountGreaterThanEqual(
            CustomerTransfer.TransferStatus status, int minRetries);
}
