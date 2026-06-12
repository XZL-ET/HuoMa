package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.GlobalAgentPool;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
