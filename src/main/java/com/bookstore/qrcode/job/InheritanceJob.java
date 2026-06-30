package com.bookstore.qrcode.job;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在职继承定时任务。
 * <p>
 * <b>白天（{@code dayStartHour}:00–{@code dayEndHour}:00）：</b>
 * 每 15 分钟执行一次（{@link #executeDaytimeBatch}），
 * 将过去 15 分钟内接待员添加的客户批量转移给服务老师。
 * 客户添加后延迟 15 分钟再转，避免即时打扰。
 * </p>
 * <p>
 * <b>夜间（{@code dayEndHour}:00–次日 {@code dayStartHour}:00）：</b>
 * 次日 08:30 执行一次（{@link #executeNightBatch}），
 * 将夜间窗口内添加的客户批量转移，避免半夜打扰。
 * </p>
 * <p>
 * 转移事件通过 XADD 写入 Redis Stream
 * {@value RedisConfig#TRANSFER_STREAM_KEY}，由 {@code TransferWorker} 异步消费执行。
 * </p>
 *
 * <h3>调度配置</h3>
 * <ul>
 *   <li>白天：每 15 分钟（硬编码，精确到 :00 :15 :30 :45）</li>
 *   <li>夜间：默认每天 08:30，可通过 {@code app.inheritance.cron} 配置</li>
 *   <li>窗口：{@code app.inheritance.day-start-hour} / {@code app.inheritance.day-end-hour}</li>
 * </ul>
 *
 * @author Bookstore Dev
 * @since 2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InheritanceJob {

    private final QrCodeRepository qrCodeRepo;
    private final QrAgentRepository qrAgentRepo;
    private final CustomerRepository customerRepo;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Value("${app.inheritance.day-start-hour:8}")
    private int dayStartHour;
    @org.springframework.beans.factory.annotation.Value("${app.inheritance.day-end-hour:21}")
    private int dayEndHour;

    /** Redis key: 自动在职继承开关，值 "true"=开启 "false"=暂停，key 不存在视为开启 */
    public static final String AUTO_ENABLED_KEY = "inheritance:auto:enabled";

    /**
     * 读取自动在职继承开关状态。
     * @return true=自动继承已开启，false=已暂停
     */
    private boolean isAutoEnabled() {
        String val = redisTemplate.opsForValue().get(AUTO_ENABLED_KEY);
        return val == null || !"false".equals(val); // key 不存在或非 false 均为开启
    }

    // ================================================================
    //  启动补偿：防止服务重启导致漏掉一个 15 分钟窗口
    // ================================================================

    /**
     * 启动时补偿最近 30 分钟内可能被漏掉的转移窗口。
     * <p>
     * 仅在白天窗口内执行（夜间由 08:30 定时批次兜底）。
     * 补偿窗口设为 30 分钟（覆盖最多 2 个遗漏的 15 分钟批次），
     * {@link com.bookstore.qrcode.service.TransferService#initiate} 的去重逻辑
     * 会自动跳过已存在转移记录的客户，不会重复转移。
     * </p>
     */
    @PostConstruct
    public void compensateMissedWindows() {
        LocalTime now = LocalTime.now();
        LocalTime dayStart = LocalTime.of(dayStartHour, 0);
        LocalTime dayEnd = LocalTime.of(dayEndHour, 0);

        // 夜间不补偿，由 08:30 夜间批次统一处理
        if (now.isBefore(dayStart) || now.isAfter(dayEnd)) {
            log.info("启动补偿跳过（夜间时段），交由 08:30 定时批次处理");
            return;
        }

        // 补偿最近 30 分钟，但窗口起点不早于 dayStartHour:00（避免越界到夜间）
        LocalDateTime compensateEnd = LocalDateTime.now();
        LocalDateTime compensateStart = compensateEnd.minusMinutes(30);
        if (compensateStart.toLocalTime().isBefore(dayStart)) {
            compensateStart = LocalDateTime.now().withHour(dayStartHour)
                .withMinute(0).withSecond(0).withNano(0);
        }

        log.info("启动补偿：处理 {} ~ {} 的遗漏转移", compensateStart, compensateEnd);
        processTransferWindow(compensateStart, compensateEnd, "启动补偿");
    }

    // ================================================================
    //  白天：每 15 分钟批量转移（延迟 15 分钟）
    // ================================================================

    /**
     * 白天在职继承 — 每 15 分钟执行一次。
     * <p>
     * 仅在白天窗口内（{@code dayStartHour}:00–{@code dayEndHour}:00）生效。
     * 将过去 15 分钟内接待员添加的客户批量 XADD 到 TRANSFER_STREAM。
     * 首轮（如 08:00）会被跳过，因为前 15 分钟属于夜间窗口；
     * 实际首轮为 08:15（处理 08:00–08:15 添加的客户）。
     * </p>
     */
    @Scheduled(cron = "0 */15 * * * *")
    public void executeDaytimeBatch() {
        if (!isAutoEnabled()) return;
        LocalTime now = LocalTime.now();
        LocalTime dayStart = LocalTime.of(dayStartHour, 0);
        LocalTime dayEnd = LocalTime.of(dayEndHour, 0);

        // 不在白天窗口内则跳过（now >= dayStart 且 now <= dayEnd）
        // 注意：20:45 批次之后下一轮是 21:00，必须让 21:00 批次处理 20:45-21:00，
        // 所以用 isAfter 而非 !isBefore，保留 now==dayEnd 边界
        if (now.isBefore(dayStart) || now.isAfter(dayEnd)) {
            return;
        }

        // 窗口起点 = 15 分钟前；若该时间仍在夜间则跳过（首轮保护）
        LocalDateTime windowStart = LocalDateTime.now().minusMinutes(15);
        if (windowStart.toLocalTime().isBefore(dayStart)) {
            return; // 前 15 分钟属于夜间，由夜间批量处理
        }
        LocalDateTime windowEnd = LocalDateTime.now();

        processTransferWindow(windowStart, windowEnd,
            String.format("白天 %02d:%02d 批次", now.getHour(), now.getMinute()));
    }

    // ================================================================
    //  夜间：次日 08:30 批量转移
    // ================================================================

    /**
     * 夜间在职继承 — 每日 08:30 执行。
     * <p>
     * 将前一日 {@code dayEndHour}:00 至今日 {@code dayStartHour}:00 之间
     * 接待员添加的客户批量转移。白天窗口的客户已由 15 分钟批次处理，
     * 本任务仅覆盖夜间延迟部分。
     * </p>
     */
    @Scheduled(cron = "${app.inheritance.cron:0 30 8 * * *}")
    public void executeNightBatch() {
        if (!isAutoEnabled()) { log.info("自动在职继承已暂停，跳过夜间批次"); return; }
        log.info("在职继承定时任务开始（夜间窗口批量转移）");
        // 夜间窗口：前一日 dayEndHour:00 ~ 今日 (dayStartHour-1):59:59
        LocalDateTime nightStart = LocalDateTime.now()
            .minusDays(1).withHour(dayEndHour).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime nightEnd = LocalDateTime.now()
            .withHour(dayStartHour).withMinute(0).withSecond(0).withNano(0)
            .minusSeconds(1);

        processTransferWindow(nightStart, nightEnd, "夜间窗口批量");
    }

    // ================================================================
    //  公共逻辑
    // ================================================================

    /**
     * 在指定时间窗口内扫描所有活码，将接待员添加的客户 XADD 到转移流。
     *
     * @param windowStart 窗口起始（含）
     * @param windowEnd   窗口结束（含，BETWEEN 语义）
     * @param windowLabel 日志标签
     */
    private void processTransferWindow(LocalDateTime windowStart, LocalDateTime windowEnd,
                                        String windowLabel) {
        List<QrCode> activeQrs = qrCodeRepo.findByStatus(QrCode.QrCodeStatus.active);
        int totalTransfers = 0;
        int skippedNoReceptionist = 0;
        int skippedNoService = 0;

        for (QrCode qr : activeQrs) {
            try {
                // 查找该活码下所有联系人，仅排除已移除（removed），保留 active/full
                List<QrAgent> agents = qrAgentRepo.findByQrCodeId(qr.getId()).stream()
                    .filter(a -> a.getStatus() != QrAgent.AgentStatus.removed)
                    .toList();

                // 筛选接待员（receptionist 或 dual 角色）
                List<QrAgent> receptionists = agents.stream()
                    .filter(a -> a.getRole() == QrAgent.AgentRole.receptionist
                              || a.getRole() == QrAgent.AgentRole.dual)
                    .toList();

                // 查找服务老师（service 或 dual 角色）
                QrAgent serviceTeacher = agents.stream()
                    .filter(a -> a.getRole() == QrAgent.AgentRole.service
                              || a.getRole() == QrAgent.AgentRole.dual)
                    .findFirst().orElse(null);

                if (receptionists.isEmpty()) {
                    skippedNoReceptionist++;
                    continue;
                }
                if (serviceTeacher == null) {
                    skippedNoService++;
                    continue;
                }

                for (QrAgent rec : receptionists) {
                    if (rec.getAgentUserid().equals(serviceTeacher.getAgentUserid())) {
                        continue; // 跳过自己转自己（dual 角色）
                    }
                    List<Customer> customers = customerRepo
                        .findByAddedAgentAndSchoolIdAndAddTimeBetween(
                            rec.getAgentUserid(), qr.getSchoolId(), windowStart, windowEnd);

                    for (Customer c : customers) {
                        Map<String, Object> event = new LinkedHashMap<>();
                        event.put("customer_id", c.getId().toString());
                        event.put("from_userid", rec.getAgentUserid());
                        event.put("to_userid", serviceTeacher.getAgentUserid());
                        event.put("external_userid", c.getExternalUserid());
                        event.put("state", qr.getSchoolId());

                        redisTemplate.opsForStream().add(
                            RedisConfig.TRANSFER_STREAM_KEY,
                            Map.of("event", objectMapper.writeValueAsString(event)));
                        totalTransfers++;
                    }
                }
            } catch (Exception e) {
                log.error("在职继承失败: qrCodeId={}", qr.getId(), e);
            }
        }
        if (totalTransfers > 0 || skippedNoReceptionist > 0 || skippedNoService > 0) {
            log.info("在职继承（{}）: 共发起 {} 条转移, 无接待员跳过 {} 条, 无服务老师跳过 {} 条, 窗口=[{}, {}]",
                windowLabel, totalTransfers, skippedNoReceptionist, skippedNoService,
                windowStart, windowEnd);
        }
    }
}
