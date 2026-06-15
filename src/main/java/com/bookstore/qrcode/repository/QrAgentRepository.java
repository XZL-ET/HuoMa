package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.QrAgent;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
