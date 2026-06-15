package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.GlobalAgentPool;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
