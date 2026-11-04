package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.QrRotateLog;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.repository.QrRotateLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 部门准入：活码撤人核心。
 *
 * <p>当部门准入白名单收紧后，把不再允许的接待员从活码上撤下。
 * 本服务只做「撤回」动作，不负责预览、编排或分布式锁
 * （锁由上层编排层持有，因此两个方法均以 {@link Propagation#REQUIRES_NEW} 运行）。</p>
 *
 * <h3>核心不变量：活码至少保留 1 名 active 成员</h3>
 * <p>{@link #removeReceptionistsFromQr(Long, Set)} 先计算撤人后剩余的 active 成员数；
 * 若不足 1 人，则从全局员工池逐名补位（{@code takeStandby}），
 * 补位成功后才真正把目标接待员标记为 {@code removed}。
 * 若池已枯竭，则放弃本次撤人并返回 {@code skipped=true}，原活码保持不变，
 * 避免出现无任何 active 成员、客户扫码无人可分配的活码。</p>
 *
 * <h3>只撤接待员，不动其他角色</h3>
 * <ul>
 *   <li>{@code service} / {@code dual}（服务老师）即使命中 disallowed 列表也不动，
 *       它们的去留由其他流程负责；</li>
 *   <li>{@code full} 状态的接待员由 {@link #removeFullReceptionists(Collection)} 直接撤回，
 *       不参与补位计算（补位只为保证 active 成员数量）。</li>
 * </ul>
 *
 * <p>补位时排除该活码上<b>所有</b>已绑定的 userid（无论状态），
 * 因为 {@code (qr_code_id, agent_userid)} 唯一；同时不复活历史 removed 行，
 * 一律新建 active 接待员绑定。</p>
 *
 * @author Bookstore Dev
 * @since 2026-11-02
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepartmentWhitelistRemovalService {

    private final QrCodeRepository qrCodeRepo;
    private final QrAgentRepository qrAgentRepo;
    private final QrRotateLogRepository rotateLogRepo;
    private final GlobalAgentPoolService poolService;

    /**
     * 撤人结果。
     *
     * @param removed    实际撤回的 active 接待员数量
     * @param backfilled 补位新增的接待员数量
     * @param skipped    是否因池枯竭而整体跳过（此时 removed 为 0，原活码不变）
     * @param skipReason 跳过原因，未跳过时为 {@code null}
     */
    public record QrRemovalOutcome(int removed, int backfilled, boolean skipped, String skipReason) {}

    /**
     * 从指定活码撤下不再被部门白名单允许的 active 接待员。
     *
     * <p>撤人后若 active 成员不足 1 人，先从全局池补位；池枯竭则整体跳过。</p>
     *
     * @param qrCodeId          活码 ID
     * @param disallowedUserids 不允许的 userid 集合
     * @return 撤人结果；无匹配接待员时返回 {@code (0,0,false,null)}
     * @throws RuntimeException 活码不存在
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public QrRemovalOutcome removeReceptionistsFromQr(Long qrCodeId, Set<String> disallowedUserids) {
        QrCode qr = qrCodeRepo.findById(qrCodeId)
            .orElseThrow(() -> new RuntimeException("活码不存在: " + qrCodeId));

        List<QrAgent> all = qrAgentRepo.findByQrCodeId(qrCodeId);
        List<QrAgent> active = all.stream()
            .filter(qa -> qa.getStatus() == QrAgent.AgentStatus.active)
            .toList();
        List<QrAgent> doomed = active.stream()
            .filter(qa -> qa.getRole() == QrAgent.AgentRole.receptionist)
            .filter(qa -> disallowedUserids != null
                && disallowedUserids.contains(qa.getAgentUserid()))
            .toList();
        if (doomed.isEmpty()) {
            return new QrRemovalOutcome(0, 0, false, null);
        }

        // 排除该活码上所有已绑定的 userid（含 removed），避免补位撞唯一索引
        Set<String> exclude = all.stream()
            .map(QrAgent::getAgentUserid)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        // sortOrder 按活码内最大值递增，而非全表最大值
        int sortOrder = all.stream()
            .mapToInt(qa -> qa.getSortOrder() == null ? 0 : qa.getSortOrder())
            .max()
            .orElse(0);

        int remaining = active.size() - doomed.size();
        int backfilled = 0;
        while (remaining < 1) {
            GlobalAgentPool pool = poolService.takeStandby(exclude, qr.getDepartmentId());
            if (pool == null) {
                log.warn("部门准入撤人跳过：全局池枯竭无法补位, qrCodeId={}", qrCodeId);
                return new QrRemovalOutcome(0, 0, true, "全局池枯竭，无法补位");
            }
            sortOrder++;
            qrAgentRepo.save(QrAgent.builder()
                .qrCodeId(qrCodeId)
                .agentUserid(pool.getAgentUserid())
                .role(QrAgent.AgentRole.receptionist)
                .dailyMax(pool.getDailyMax())
                .sortOrder(sortOrder)
                .status(QrAgent.AgentStatus.active)
                .temporary(false)
                .build());
            exclude.add(pool.getAgentUserid());
            backfilled++;
            remaining++;
            log.info("部门准入补位：qrCodeId={}, userid={}", qrCodeId, pool.getAgentUserid());
        }

        for (QrAgent qa : doomed) {
            qa.setStatus(QrAgent.AgentStatus.removed);
        }
        qrAgentRepo.saveAll(doomed);
        rotateLogRepo.saveAll(doomed.stream()
            .map(qa -> QrRotateLog.builder()
                .qrCodeId(qrCodeId)
                .fromUserid(qa.getAgentUserid())
                .reason("部门准入白名单移出")
                .build())
            .toList());

        log.info("部门准入撤人完成：qrCodeId={}, removed={}, backfilled={}",
            qrCodeId, doomed.size(), backfilled);
        return new QrRemovalOutcome(doomed.size(), backfilled, false, null);
    }

    /**
     * 直接把指定绑定中的 full 接待员标记为 removed。
     *
     * <p>只处理 {@code status == full && role == receptionist} 的行；
     * 其他状态或角色（含服务老师）一律忽略，不做补位。</p>
     *
     * @param qrAgentIds qr_agent 主键集合
     * @return 实际被改为 removed 的行数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int removeFullReceptionists(Collection<Long> qrAgentIds) {
        if (qrAgentIds == null || qrAgentIds.isEmpty()) {
            return 0;
        }
        List<QrAgent> toRemove = new ArrayList<>();
        for (Long id : qrAgentIds) {
            if (id == null) {
                continue;
            }
            QrAgent qa = qrAgentRepo.findById(id).orElse(null);
            if (qa == null) {
                continue;
            }
            if (qa.getStatus() == QrAgent.AgentStatus.full
                && qa.getRole() == QrAgent.AgentRole.receptionist) {
                qa.setStatus(QrAgent.AgentStatus.removed);
                toRemove.add(qa);
            }
        }
        if (toRemove.isEmpty()) {
            return 0;
        }
        qrAgentRepo.saveAll(toRemove);
        log.info("部门准入撤 full 接待员：count={}", toRemove.size());
        return toRemove.size();
    }
}
