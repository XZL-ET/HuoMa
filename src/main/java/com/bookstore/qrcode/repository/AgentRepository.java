package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.Agent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 员工 数据访问层。
 *
 * <p>提供对 {@link Agent} 表的 CRUD 操作和自定义查询方法。
 * 继承 Spring Data JPA 的 {@link JpaRepository}，自动获得基础增删改查能力。
 * 员工表（agent）是系统的核心人员主数据，存储企业微信中的员工账号信息，
 * 支持按综合状态和角色进行筛选查询。</p>
 *
 * @author Bookstore Dev
 * @since 1.0
 */
public interface AgentRepository extends JpaRepository<Agent, String> {

    /**
     * 按员工综合状态查询员工列表。
     * <p>
     * 用于根据员工的工作状态进行筛选：
     * <ul>
     *   <li><b>normal</b> — 正常：可分配客户；</li>
     *   <li><b>warning</b> — 预警：接近上限但仍在接待；</li>
     *   <li><b>blocked</b> — 已停用：不再分配新客户；</li>
     *   <li><b>melted</b> — 已熔断：因异常行为暂停接待。</li>
     * </ul>
     * 例如，活码分配客户时只查询 normal 状态的员工。
     * </p>
     *
     * @param status 员工综合状态
     * @return 匹配该状态的员工列表
     */
    List<Agent> findByOverallStatus(Agent.OverallStatus status);

    /**
     * 按员工角色查询员工列表。
     * <p>
     * 用于根据角色筛选特定职能的员工：
     * <ul>
     *   <li><b>receptionist</b> — 接待员：仅负责接待新客户；</li>
     *   <li><b>service</b> — 服务老师：仅服务已有客户；</li>
     *   <li><b>dual</b> — 双重角色：可接待新客户也提供服务。</li>
     * </ul>
     * 例如，查找所有接待员用于生成接待汇总报表。
     * </p>
     *
     * @param role 员工角色（receptionist / service / dual）
     * @return 匹配该角色的员工列表
     */
    List<Agent> findByRole(Agent.AgentRole role);

    /**
     * 使用悲观写锁按 userid 查询员工。
     *
     * <p>在需要安全更新员工状态的场景（熔断、暂停等）使用此方法，
     * 通过 {@code SELECT ... FOR UPDATE} 确保同一时刻只有一个事务可以修改该行，
     * 避免高并发下多个线程同时读→改→写造成死锁。</p>
     *
     * @param userid 员工唯一标识
     * @return 员工实体（可能为空）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Agent a WHERE a.userid = :userid")
    Optional<Agent> findByIdForUpdate(@Param("userid") String userid);

    /** 按姓名模糊搜索 — 替代 findAll 后 Java 过滤 */
    List<Agent> findByNameContaining(String name);
}
