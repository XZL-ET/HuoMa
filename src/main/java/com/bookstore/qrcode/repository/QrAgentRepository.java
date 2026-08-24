package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.QrAgent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 活码接待员数据访问层。
 * <p>
 * 提供对 qr_agent（活码-接待员关联）表的 CRUD 操作和自定义查询。
 * 用于管理每个活码下分配的接待员列表、角色、排序及状态。
 * </p>
 *
 * @author Bookstore Dev
 * @since 1.0
 */
public interface QrAgentRepository extends JpaRepository<QrAgent, Long> {

    /**
     * 根据活码 ID 查询该活码下的所有接待员记录。
     *
     * @param qrCodeId 活码 ID
     * @return 该活码的接待员列表
     */
    List<QrAgent> findByQrCodeId(Long qrCodeId);

    /**
     * 根据活码 ID 查询所有接待员，并按排序字段升序返回。
     * <p>
     * 用于活码分配客户时按预设顺序轮转接待员。
     * </p>
     *
     * @param qrCodeId 活码 ID
     * @return 按 sortOrder 排序后的接待员列表
     */
    List<QrAgent> findByQrCodeIdOrderBySortOrder(Long qrCodeId);

    /**
     * 根据活码 ID 和接待员状态查询符合条件的记录。
     * <p>
     * 用于筛选某个活码下当前「启用」或「停用」的接待员，
     * 例如活码分配时只取状态为 ACTIVE 的接待员。
     * </p>
     *
     * @param qrCodeId 活码 ID
     * @param status   接待员状态（ACTIVE / INACTIVE）
     * @return 符合条件的接待员列表
     */
    List<QrAgent> findByQrCodeIdAndStatus(Long qrCodeId, QrAgent.AgentStatus status);

    /**
     * 根据活码 ID 和接待员企业微信用户 ID 查询唯一记录。
     * <p>
     * 用于判断某个员工是否已添加到指定活码的接待员名单中，避免重复添加。
     * </p>
     *
     * @param qrCodeId    活码 ID
     * @param agentUserid 接待员的企业微信用户 ID
     * @return 匹配的接待员记录，不存在则返回 {@link Optional#empty()}
     */
    Optional<QrAgent> findByQrCodeIdAndAgentUserid(Long qrCodeId, String agentUserid);

    /**
     * 根据接待员企业微信用户 ID 查询该员工参与的所有活码记录。
     * <p>
     * 用于查看某个员工被分配到了哪些活码下担任接待员。
     * </p>
     *
     * @param agentUserid 接待员的企业微信用户 ID
     * @return 该员工关联的活码接待员记录列表
     */
    List<QrAgent> findByAgentUserid(String agentUserid);

    /**
     * 根据接待员企业微信用户 ID 和状态查询关联记录。
     * <p>
     * 用于筛选某个员工当前「启用」或「停用」的活码接待关系。
     * </p>
     *
     * @param agentUserid 接待员的企业微信用户 ID
     * @param status      接待员状态（ACTIVE / INACTIVE）
     * @return 符合条件的活码接待员记录列表
     */
    List<QrAgent> findByAgentUseridAndStatus(String agentUserid, QrAgent.AgentStatus status);

    /**
     * 根据接待员状态查询所有符合条件的记录。
     * <p>
     * 用于全局查询当前处于「启用」或「停用」状态的所有接待员关联，
     * 常用于系统管理或批量操作场景。
     * </p>
     *
     * @param status 接待员状态（ACTIVE / INACTIVE）
     * @return 指定状态的接待员记录列表
     */
    List<QrAgent> findByStatus(QrAgent.AgentStatus status);

    /**
     * 根据活码 ID 和接待员角色查询记录。
     * <p>
     * 用于区分同一活码下不同角色的接待员（如「服务老师」和「后备接待员」），
     * 以便按角色执行不同的分配逻辑或展示不同的界面。
     * </p>
     *
     * @param qrCodeId 活码 ID
     * @param role     接待员角色
     * @return 符合条件的接待员列表
     */
    List<QrAgent> findByQrCodeIdAndRole(Long qrCodeId, QrAgent.AgentRole role);

    /**
     * 根据活码 ID 和接待员企业微信用户 ID 删除关联记录。
     * <p>
     * 用于从某个活码中移除指定的接待员，支持批量删除操作。
     * </p>
     *
     * @param qrCodeId    活码 ID
     * @param agentUserid 接待员的企业微信用户 ID
     */
    void deleteByQrCodeIdAndAgentUserid(Long qrCodeId, String agentUserid);

    /** 按 agentUserid 列表批量查询 — 替代 findAll 全表加载 */
    List<QrAgent> findByAgentUseridIn(Collection<String> agentUserids);

    /**
     * 按 qrCodeId 列表批量查询。
     * <p>
     * 用于全局统计等场景下一次性加载多个活码的接待员列表，
     * 避免对每个活码单独执行 {@link #findByQrCodeId(Long)} 造成的 N+1 问题。
     * </p>
     *
     * @param qrCodeIds 活码 ID 集合
     * @return 所有匹配的接待员记录列表
     */
    List<QrAgent> findByQrCodeIdIn(Collection<Long> qrCodeIds);

    /**
     * 批量将指定员工的活码关联标记为已移除。
     * <p>在企微通讯录同步发现员工离职时调用，防止已离职员工的 userid
     * 继续被推送到企微 API 导致 60111 错误。</p>
     *
     * @param agentUserids 离职员工的 userid 列表
     * @return 实际更新的行数
     */
    @Modifying
    @Transactional
    @Query("UPDATE QrAgent qa SET qa.status = 'removed', qa.updatedAt = CURRENT_TIMESTAMP "
         + "WHERE qa.agentUserid IN :agentUserids AND qa.status = 'active'")
    int batchRemoveByAgentUserids(@Param("agentUserids") Collection<String> agentUserids);

    /**
     * 查找指定员工中担任服务老师/双角色的活码关联。
     * <p>用于离职级联清理时区分处理：服务老师不下码只告警。</p>
     */
    @Query("SELECT DISTINCT qa.agentUserid FROM QrAgent qa "
         + "WHERE qa.agentUserid IN :agentUserids "
         + "AND qa.status = 'active' "
         + "AND (qa.role = 'service' OR qa.role = 'dual')")
    List<String> findServiceUseridsIn(@Param("agentUserids") Collection<String> agentUserids);
}
