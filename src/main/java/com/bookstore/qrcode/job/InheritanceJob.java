package com.bookstore.qrcode.job;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.QrAgent;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.QrAgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在职继承定时任务。
 * <p>
 * 每日定时扫描所有 active 状态的活码，将每个接待员（receptionist）当天添加的客户
 * 转移给该活码的服务老师（service）。转移事件通过 XADD 写入 Redis Stream
 * {@value RedisConfig#TRANSFER_STREAM_KEY}，由 {@code TransferWorker} 异步消费执行。
 * </p>
 *
 * <h3>执行逻辑</h3>
 * <ol>
 *   <li>查询所有 active 状态的活码</li>
 *   <li>对每个活码，查找其 receptionist 和 service 角色联系人</li>
 *   <li>若缺少 receptionist 或 service，跳过该活码</li>
 *   <li>对每个 receptionist，查询其今天添加的客户</li>
 *   <li>逐一将客户转移事件 XADD 到 TRANSFER_STREAM</li>
 * </ol>
 *
 * <h3>调度配置</h3>
 * 默认每天凌晨 02:00 执行，可通过 {@code app.inheritance.cron} 配置项覆盖。
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

    /**
     * 执行在职继承扫描。
     * <p>
     * 定时触发（默认每日 02:00），扫描所有 active 活码，
     * 将接待员当天添加的客户转移给服务老师。
     * </p>
     */
    @Scheduled(cron = "${app.inheritance.cron:0 0 2 * * *}")
    public void execute() {
        log.info("在职继承定时任务开始");
        List<QrCode> activeQrs = qrCodeRepo.findByStatus(QrCode.QrCodeStatus.active);
        int totalTransfers = 0;

        for (QrCode qr : activeQrs) {
            try {
                // 查找该活码下的所有联系人
                List<QrAgent> agents = qrAgentRepo.findByQrCodeId(qr.getId());

                // 筛选接待员（receptionist 角色）
                List<QrAgent> receptionists = agents.stream()
                    .filter(a -> a.getRole() == QrAgent.AgentRole.receptionist)
                    .toList();

                // 查找服务老师（service 角色）
                QrAgent serviceTeacher = agents.stream()
                    .filter(a -> a.getRole() == QrAgent.AgentRole.service)
                    .findFirst().orElse(null);

                // 缺少接待员或服务老师则跳过
                if (receptionists.isEmpty() || serviceTeacher == null) {
                    continue;
                }

                // 当天 00:00:00 作为时间下限
                LocalDateTime todayStart = LocalDateTime.now()
                    .withHour(0).withMinute(0).withSecond(0).withNano(0);

                for (QrAgent rec : receptionists) {
                    // 查找该接待员今天添加的客户
                    List<Customer> customers = customerRepo
                        .findByAddedAgentAndAddTimeAfter(rec.getAgentUserid(), todayStart);

                    for (Customer c : customers) {
                        // 构建转移事件
                        Map<String, Object> event = new LinkedHashMap<>();
                        event.put("customer_id", c.getId().toString());
                        event.put("from_userid", rec.getAgentUserid());
                        event.put("to_userid", serviceTeacher.getAgentUserid());
                        event.put("external_userid", c.getExternalUserid());
                        event.put("state", qr.getSchoolId());

                        // XADD 到转让流，由 TransferWorker 异步消费
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
        log.info("在职继承定时任务完成: 共发起 {} 条转移", totalTransfers);
    }
}
