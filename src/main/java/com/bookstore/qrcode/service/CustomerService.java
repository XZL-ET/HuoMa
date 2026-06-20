package com.bookstore.qrcode.service;

import com.bookstore.qrcode.config.RedisConfig;
import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户服务，负责客户的新增、更新、查询、数据修复等核心业务逻辑。
 *
 * <p>主要功能包括：
 * <ul>
 *   <li>处理企微回调中的客户新增/更新（{@link #upsertFromCallback}）</li>
 *   <li>处理客户删除事件（{@link #handleDelete}）</li>
 *   <li>客户搜索与详情查询（{@link #search} / {@link #getById}）</li>
 *   <li>存量客户数据批量修复（{@link #repairCustomerData}）</li>
 *   <li>手动创建测试客户（{@link #createManual}）</li>
 * </ul>
 *
 * <p>涉及的实体：{@link Customer}、{@link QrCode}、{@link CustomerTag}
 *
 * @author Bookstore Dev
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepo;
    private final QrCodeRepository qrCodeRepo;
    private final CustomerTagRepository customerTagRepo;
    private final TagRepository tagRepo;
    private final WecomApiClient wecomApi;
    private final StringRedisTemplate redisTemplate;
    private final MessageGuardService messageGuardService;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    /**
     * 从企微回调创建或更新客户。
     *
     * <p>核心逻辑：
     * <ol>
     *   <li>根据 externalUserId 查询是否已存在该客户</li>
     *   <li>从 state 参数中提取学校ID，反查对应的活码记录</li>
     *   <li>若客户已存在：更新归属员工和来源活码；若此前为 deleted 状态则重新激活为 active</li>
     *   <li>若客户不存在：调用企微 API 获取客户详情（姓名、头像、unionid 等），构建新客户记录并保存</li>
     * </ol>
     *
     * @param externalUserId 企微客户外部用户ID（每个企业在每个微信客户上唯一）
     * @param userId         当前接待员工的企微用户ID
     * @param state          自定义参数，通常为学校ID，用于定位来源活码
     * @return 客户记录的主键 ID
     * @throws RuntimeException 当保存客户失败时抛出（由 JPA 层传播）
     */
    @Transactional
    public Long upsertFromCallback(String externalUserId, String userId, String state) {
        // 查询该外部用户是否已存在（新客户 vs 老客户走不同分支）
        Customer existing = customerRepo.findByExternalUserid(externalUserId).orElse(null);

        // ===== 从 state 解析来源活码 =====
        // state 中存放的是学校ID，通过学校ID反查活码记录，拿到活码ID和学校ID
        Long qrCodeId = null;
        String schoolId = null;
        if (state != null && !state.isBlank()) {
            QrCode qr = qrCodeRepo.findBySchoolId(state).orElse(null);
            if (qr != null) {
                qrCodeId = qr.getId();
                schoolId = state;
            } else {
                // state 未匹配到任何活码，可能是回调参数异常或活码已被删除，仅记录日志不影响流程
                log.warn("回调 state 未匹配到活码 school_id: state={}, external={}", state, externalUserId);
            }
        }

        if (existing != null) {
            // ===== 已有客户：更新归属与来源 =====
            // 客户可能扫了新的活码或换了接待员工，需要更新关联信息
            existing.setCurrentAgent(userId);
            existing.setUpdatedAt(LocalDateTime.now());
            // 如果新回调中有匹配的活码，更新来源活码和学校信息（保留历史来源）
            if (qrCodeId != null) {
                existing.setSourceQrId(qrCodeId);
                existing.setSchoolId(schoolId);
            }
            // 如果客户之前因删除事件被标记为 deleted，重新激活为 active
            // 场景：客户删除后又重新添加（重新扫码），需恢复可用状态
            if (existing.getStatus() == Customer.CustomerStatus.deleted) {
                existing.setStatus(Customer.CustomerStatus.active);
                log.info("重新激活已删除客户: external={}", externalUserId);
            }
            customerRepo.save(existing);
            return existing.getId();
        }

        // ===== 新客户：快速写入稀疏记录，由 DataFillWorker 异步补全 =====
        // 不在此处调企微 API（GET /externalcontact/get），避免 200ms+
        // 的网络等待阻塞消费主链路

        // Redis 轻量锁防并发重复插入（多线程安全）
        String lockKey = "customer:lock:" + externalUserId;
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", Duration.ofSeconds(10));

        if (Boolean.FALSE.equals(locked)) {
            // 另一个线程正在创建同一客户 → 等待并重试查询（最多 10 次，指数退避）
            for (int attempt = 0; attempt < 10; attempt++) {
                try { Thread.sleep(Math.min(50L << attempt, 500L)); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                Customer retry = customerRepo.findByExternalUserid(externalUserId).orElse(null);
                if (retry != null) {
                    retry.setCurrentAgent(userId);
                    retry.setUpdatedAt(LocalDateTime.now());
                    if (qrCodeId != null) {
                        retry.setSourceQrId(qrCodeId);
                        retry.setSchoolId(schoolId);
                    }
                    customerRepo.save(retry);
                    return retry.getId();
                }
            }
            // 重试耗尽：赢家事务可能异常回滚，入 DLQ 由 PatrolWorker 重放
            log.error("[ALERT] upsert 锁竞争超限，消息入 DLQ: external={}", externalUserId);
            try {
                Map<String, String> dlqEntry = new LinkedHashMap<>();
                dlqEntry.put("source", "upsert-lock-exhaustion");
                dlqEntry.put("external_userid", externalUserId);
                dlqEntry.put("user_id", userId);
                dlqEntry.put("qr_code_id", qrCodeId != null ? String.valueOf(qrCodeId) : "");
                dlqEntry.put("school_id", schoolId != null ? schoolId : "");
                dlqEntry.put("timestamp", String.valueOf(System.currentTimeMillis()));
                messageGuardService.sendToDlq(RedisConfig.CALLBACK_STREAM_KEY, dlqEntry);
            } catch (Exception dlqEx) {
                log.error("[CRITICAL] DLQ 写入也失败，消息永久丢失: external={}", externalUserId, dlqEx);
            }
            return null;
        }

        // 事务提交后释放锁，防止锁提前释放导致竞态窗口
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redisTemplate.delete(lockKey);
                }
            });

        try {
            Customer customer = Customer.builder()
                .externalUserid(externalUserId)
                .name("未知")            // 占位，DataFillWorker 异步补全
                .type(1)
                .addedAgent(userId)
                .currentAgent(userId)
                .sourceQrId(qrCodeId)
                .schoolId(schoolId)
                .status(Customer.CustomerStatus.active)
                .addTime(LocalDateTime.now())
                .build();
            customer = customerRepo.save(customer);

            // 发布数据补全事件 → DataFillWorker 异步消费
            try {
                Map<String, Object> fillEvent = new LinkedHashMap<>();
                fillEvent.put("external_userid", externalUserId);
                fillEvent.put("customer_id", customer.getId());
                redisTemplate.opsForStream().add(
                    RedisConfig.DATAFILL_STREAM_KEY,
                    Map.of("event", objectMapper.writeValueAsString(fillEvent)));
            } catch (Exception e) {
                // Redis publish 失败 → customer 已持久化但 DataFill 永不触发
                // 标记为需要 repair 兜底（name="未知" 的客户会被 repairCustomerData 扫描修复）
                log.error("[ALERT] DataFill 事件发布失败，客户数据将不完整: external={}, customerId={}",
                    externalUserId, customer.getId(), e);
            }

            return customer.getId();

        } catch (DataIntegrityViolationException e) {
            // 并发插入冲突 → 清除 Hibernate Session 污染（防止 session 被标记为 rollback-only）
            entityManager.clear();
            // 此时赢家已提交，查库必然能查到 → 更新而非插入
            log.warn("客户并发插入冲突，清除 Session 后查库更新: external={}", externalUserId);
            Customer retry = customerRepo.findByExternalUserid(externalUserId).orElse(null);
            if (retry != null) {
                retry.setCurrentAgent(userId);
                retry.setUpdatedAt(LocalDateTime.now());
                if (qrCodeId != null) {
                    retry.setSourceQrId(qrCodeId);
                    retry.setSchoolId(schoolId);
                }
                customerRepo.save(retry);
                return retry.getId();
            }
            // 极端情况：唯一键冲突但查不到记录 → 可能是 DB 异常
            log.error("[ALERT] 并发插入冲突后仍查不到客户: external={}", externalUserId);
            throw new RuntimeException("并发插入异常: " + externalUserId, e);
        }
    }

    /**
     * 处理客户删除事件。
     *
     * <p>当企微回调通知客户删除时，将对应客户记录的状态标记为 {@link Customer.CustomerStatus#deleted}，
     * 而非物理删除记录，以保留客户历史数据供后续分析或恢复。
     *
     * @param event 企微回调事件 JSON 节点，需包含 "external_userid" 字段
     */
    @Transactional
    public void handleDelete(JsonNode event) {
        // 从回调事件中提取外部用户ID
        String externalUserId = event.has("external_userid")
            ? event.get("external_userid").asText() : null;
        if (externalUserId == null) return;

        Customer customer = customerRepo.findByExternalUserid(externalUserId).orElse(null);
        if (customer != null) {
            // 标记为 deleted 而非物理删除，保留历史记录以便后续恢复或数据分析
            customer.setStatus(Customer.CustomerStatus.deleted);
            customerRepo.save(customer);
            log.info("客户已标记删除: external={}", externalUserId);
        }
    }

    /**
     * 客户搜索（管理后台）。
     *
     * <p>支持按关键词、学校、员工、状态和时间范围进行多条件组合查询，带分页。
     *
     * @param keyword      搜索关键词（匹配客户名称等）
     * @param schoolId     学校ID筛选
     * @param currentAgent 当前归属员工ID筛选
     * @param status       客户状态筛选（active / deleted）
     * @param startTime    添加时间范围（起始）
     * @param endTime      添加时间范围（结束）
     * @param pageable     分页参数
     * @return 符合条件的分页客户列表
     */
    public Page<Customer> search(String keyword, String schoolId, String currentAgent,
                                  Customer.CustomerStatus status,
                                  LocalDateTime startTime, LocalDateTime endTime,
                                  Pageable pageable) {
        return customerRepo.search(keyword, schoolId, currentAgent,
            status, startTime, endTime, pageable);
    }

    /**
     * 根据主键查询客户详情。
     *
     * @param id 客户主键 ID
     * @return 客户实体
     * @throws RuntimeException 当指定 ID 的客户不存在时抛出
     */
    public Customer getById(Long id) {
        return customerRepo.findById(id)
            .orElseThrow(() -> new RuntimeException("客户不存在: " + id));
    }

    /**
     * 查询客户关联的所有标签。
     *
     * @param customerId 客户主键 ID
     * @return 客户的标签关联列表（每个关联包含标签 ID、来源等信息）
     */
    public List<CustomerTag> getTags(Long customerId) {
        return customerTagRepo.findByCustomerId(customerId);
    }

    /**
     * 查询今日新增客户数。
     *
     * <p>以自然日为统计范围：从当日 00:00:00 到当前时刻。
     *
     * @return 今日新增客户数量
     */
    public long countToday() {
        // 单次捕获 now，避免两次调用跨午夜导致统计不准；同时截断纳秒确保包含午夜整点记录
        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDateTime todayStart = now.withHour(0).withMinute(0).withSecond(0);
        // 仅统计 active 客户，排除已删除的记录
        return customerRepo.countByAddTimeBetweenAndStatus(todayStart, now,
            Customer.CustomerStatus.active);
    }

    /**
     * 查询累计客户总数。
     *
     * @return 全部客户记录数（含 deleted 状态）
     */
    public long countTotal() {
        return customerRepo.count();
    }

    /**
     * 修复存量客户数据：从企微 API 回填缺少的字段。
     *
     * <p>适用场景：早期接入时未保存客户名称、头像、unionid 等字段，
     * 或部分客户数据因回调异常导致字段缺失。此方法遍历所有客户，
     * 调用企微 API 获取最新详情，增量补全缺失字段。</p>
     *
     * <p>仅当字段为 null（或名称为默认值"未知"）时才回填，不会覆盖已有数据。
     *
     * @return 实际修复的客户数量
     */
    @Transactional
    public int repairCustomerData() {
        // 分批处理，每批 50 条，避免全量加载到内存
        int batchSize = 50;
        int page = 0;
        int repaired = 0;
        int apiCalls = 0;
        org.springframework.data.domain.Page<Customer> customerPage;
        do {
            customerPage = customerRepo.findNeedingRepair(
                org.springframework.data.domain.PageRequest.of(page++, batchSize));
            for (Customer c : customerPage.getContent()) {
                boolean changed = false;
                try {
                    // 企微 API 限频约 600 次/分钟，每 100 次调用后休息 10 秒
                    if (apiCalls > 0 && apiCalls % 100 == 0) {
                        try { Thread.sleep(10_000); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }
                    JsonNode detail = wecomApi.getExternalContact(c.getExternalUserid());
                    apiCalls++;
                    if (detail.has("external_contact")) {
                        JsonNode ec = detail.get("external_contact");
                        if (c.getUnionid() == null && ec.has("unionid") && !ec.get("unionid").isNull()) {
                            c.setUnionid(ec.get("unionid").asText());
                            changed = true;
                        }
                        if (c.getAvatar() == null && ec.has("avatar") && !ec.get("avatar").isNull()) {
                            c.setAvatar(ec.get("avatar").asText());
                            changed = true;
                        }
                        if ("未知".equals(c.getName()) && ec.has("name")) {
                            c.setName(ec.get("name").asText());
                            changed = true;
                        }
                    }
                } catch (Exception e) {
                    log.warn("修复客户数据失败: external={}", c.getExternalUserid(), e);
                }
                if (changed) {
                    customerRepo.save(c);
                    repaired++;
                }
            }
            // 每批处理完 flush + clear，释放持久化上下文内存
            entityManager.flush();
            entityManager.clear();
        } while (customerPage.hasNext());

        log.info("客户数据修复完成: 修复{}条, API调用{}次", repaired, apiCalls);
        return repaired;
    }

    /**
     * 手动创建测试客户（开发调试用）。
     *
     * <p>当本地开发环境无法接收企微回调时，通过此方法直接插入模拟客户数据，
     * 方便开发与联调测试。生产环境不建议使用。</p>
     *
     * @param name          客户名称
     * @param externalUserid 企微外部用户ID（需唯一，不能与已有客户重复）
     * @param agentUserid   添加员工的企微用户ID
     * @param schoolId      所属学校ID
     * @param qrCodeId      来源活码ID
     * @return 创建的客户实体
     * @throws RuntimeException 当 externalUserid 已存在时抛出
     */
    @Transactional
    public Customer createManual(String name, String externalUserid,
                                  String agentUserid, String schoolId, Long qrCodeId) {
        // 校验 externalUserid 是否已存在，防止重复创建
        if (customerRepo.existsByExternalUserid(externalUserid)) {
            throw new RuntimeException("客户已存在: " + externalUserid);
        }

        Customer customer = Customer.builder()
            .externalUserid(externalUserid)
            .name(name)
            .type(1)                      // 默认为微信用户
            .addedAgent(agentUserid)      // 添加者即当前员工
            .currentAgent(agentUserid)
            .sourceQrId(qrCodeId)
            .schoolId(schoolId)
            .status(Customer.CustomerStatus.active)
            .addTime(LocalDateTime.now())
            .build();
        return customerRepo.save(customer);
    }
}
