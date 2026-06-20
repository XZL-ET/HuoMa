package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.GlobalAgentPool;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 全局员工池数据访问层。
 *
 * <p>提供按 userid 查找、按状态+优先级排序查询、按状态计数等基础操作。
 * 继承 {@link JpaRepository} 获得标准 CRUD 能力。</p>
 *
 * @author Bookstore Dev Team
 * @since 2.0.0
 */
public interface GlobalAgentPoolRepository
        extends JpaRepository<GlobalAgentPool, Long> {

    /** 按企微 userid 查找员工池记录 */
    Optional<GlobalAgentPool> findByAgentUserid(String agentUserid);

    /** 按状态查询，按优先级升序（越小越优先） */
    List<GlobalAgentPool> findByStatusOrderBySortOrder(
        GlobalAgentPool.PoolStatus status);

    /** 按状态查询（带悲观写锁，3s 超时），用于 takeStandby 原子取人 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT p FROM GlobalAgentPool p WHERE p.status = :status ORDER BY p.sortOrder ASC")
    List<GlobalAgentPool> findStandbysForUpdate(
        @Param("status") GlobalAgentPool.PoolStatus status);

    /** 统计指定状态的员工数 */
    long countByStatus(GlobalAgentPool.PoolStatus status);

    /** 取 sortOrder 最大的记录（用于队尾追加），池空时返回空 */
    Optional<GlobalAgentPool> findFirstByOrderBySortOrderDesc();

    /** 全量分页查询，按 sortOrder 升序 */
    Page<GlobalAgentPool> findAllByOrderBySortOrder(Pageable pageable);

    /** 按 userid 模糊匹配（不分页），用于收集匹配 userid 集合 */
    List<GlobalAgentPool> findByAgentUseridContaining(String agentUserid);

    /** 查询所有池中员工的 userid（轻量投影，不加载完整实体） */
    @Query("SELECT p.agentUserid FROM GlobalAgentPool p")
    List<String> findAllAgentUserids();

    // ── 分页 + 筛选方法（员工管理页面用） ──

    /** 按 userid 模糊搜索（分页），按 sortOrder 升序 */
    Page<GlobalAgentPool> findByAgentUseridContainingOrderBySortOrder(
            String agentUserid, Pageable pageable);

    /** 按状态筛选（分页），按 sortOrder 升序 */
    Page<GlobalAgentPool> findByStatusOrderBySortOrder(
            GlobalAgentPool.PoolStatus status, Pageable pageable);

    /** 按 userid 模糊搜索 + 状态筛选（分页），按 sortOrder 升序 */
    Page<GlobalAgentPool> findByAgentUseridContainingAndStatusOrderBySortOrder(
            String agentUserid, GlobalAgentPool.PoolStatus status, Pageable pageable);

    /** 按 userid 列表批量查询（分页），按 sortOrder 升序 */
    Page<GlobalAgentPool> findByAgentUseridInOrderBySortOrder(
            List<String> agentUserids, Pageable pageable);

    /** 按 userid 列表 + 状态筛选（分页），按 sortOrder 升序 */
    Page<GlobalAgentPool> findByAgentUseridInAndStatusOrderBySortOrder(
            List<String> agentUserids, GlobalAgentPool.PoolStatus status, Pageable pageable);

    // ── 批量更新（每日重置用） ──

    /**
     * 批量更新指定状态的员工池记录为新状态，并追加 sortOrder 偏移、记录重置时间。
     *
     * @param oldStatus 原状态
     * @param newStatus 新状态
     * @param offset    sortOrder 追加偏移量（用于移到队尾）
     * @param now       当前时间
     * @return 影响的记录数
     */
    @Modifying
    @Query("UPDATE GlobalAgentPool p SET p.status = :newStatus, "
            + "p.sortOrder = p.sortOrder + :offset, p.lastResetAt = :now "
            + "WHERE p.status = :oldStatus")
    int batchUpdateStatus(
            @Param("oldStatus") GlobalAgentPool.PoolStatus oldStatus,
            @Param("newStatus") GlobalAgentPool.PoolStatus newStatus,
            @Param("offset") int offset,
            @Param("now") LocalDateTime now);

    /**
     * 批量将指定状态员工的日计数清零。
     *
     * @param status 员工状态
     * @return 影响的记录数
     */
    @Modifying
    @Query("UPDATE GlobalAgentPool p SET p.dailyCurrent = 0 "
            + "WHERE p.status = :status")
    int batchResetDailyCurrent(
            @Param("status") GlobalAgentPool.PoolStatus status);
}
