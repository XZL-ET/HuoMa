package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.bookstore.qrcode.wecom.WecomApiException;
import com.bookstore.qrcode.wecom.WecomErrorCodes;
import com.bookstore.qrcode.wecom.WecomTokenExpiredException;
import com.bookstore.qrcode.wecom.WecomRateLimitException;
import com.bookstore.qrcode.wecom.WecomPermanentException;
import com.bookstore.qrcode.wecom.WecomTransientException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在职继承 — 发起转移、追踪结果、发送交接欢迎语。
 * <p>
 * 核心流程：{@link #initiate} 发起 → {@link #trackResults} 轮询企微结果 → {@link #sendTransferGreeting} 发送交接欢迎语。
 * 欢迎语按 {@link CustomerTransfer#formFilledAtTransfer} 走 A/B 分支：已填写则写备注+交接语，未填写则提醒填写。
 * 企微 API 返回 {@code TRANSFER_SUCCEED / FAIL / REFUSED / WAIT} 四种状态构成结果状态机。
 * 超过 24 小时（48 次轮询）仍未确认则标记为 {@link CustomerTransfer.TransferStatus#timeout}。
 * </p>
 *
 * @author Bookstore Dev
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private final CustomerTransferRepository transferRepo;
    private final QrAgentRepository qrAgentRepo;
    private final QrCodeRepository qrCodeRepo;
    private final CustomerRepository customerRepo;
    private final CustomerTagRepository customerTagRepo;
    private final FormSubmissionRepository formSubmissionRepo;
    private final EmployeeRepository employeeRepo;
    private final AgentRepository agentRepo;
    private final SystemConfigRepository systemConfigRepo;
    private final WecomApiClient wecomApi;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final AlertService alertService;
    private final AgentAlertRepository alertRepo;

    /** 超时阈值：发起转移后等待 24 小时 */
    private static final Duration TRANSFER_TIMEOUT = Duration.ofHours(24);

    /** 冷却期：最近 N 天内已有 timeout/rejected/retry_limit 的客户不再重转 */
    private static final Duration TRANSFER_COOLDOWN = Duration.ofDays(7);

    /** 收集表单链接，可通过 app.transfer.form-url 配置，空则使用占位符 */
    @Value("${app.transfer.form-url:}")
    private String formUrl;

    /**
     * 发起在职继承：将客户的接待关系从前任接待员转移至该活码对应的服务老师。
     * <p>
     * 流程：
     * <ol>
     *   <li>去重检查：若客户已有 pending_confirm / confirmed 的转移记录则跳过</li>
     *   <li>根据 {@code state} 查找活码</li>
     *   <li>确定目标服务老师：优先使用传入的 {@code toUserid}（若其仍为该活码活跃 service），
     *       否则从活码联系人中查找</li>
     *   <li>调用企微 API {@code transfer_customer} 发起转移</li>
     *   <li>通过 {@link #checkFormFilled} 检查客户是否已填写收集表单</li>
     *   <li>记录 {@link CustomerTransfer} 持久化到数据库，API 成功则为 pending_confirm，失败则为 api_failed</li>
     * </ol>
     * </p>
     *
     * @param customerId     客户 ID
     * @param fromUserid     原接待成员（企微 userid）
     * @param toUserid       目标服务老师（企微 userid），可为 null 则自动从活码查找
     * @param externalUserid 外部联系人 ID（企微 external_userid）
     * @param state          活码标识，用于查找对应的活码（通常为 schoolId）
     * @throws org.springframework.dao.DataAccessException 数据库写入异常
     */
    @Transactional
    public void initiate(Long customerId, String fromUserid,
                          String toUserid, String externalUserid, String state) {
        if (state == null) {
            log.warn("继承发起跳过: state 为空, customerId={}, external={}", customerId, externalUserid);
            return;
        }

        // ---- Redis 分布式锁：防止同一客户并发发起重复继承 ----
        String lockKey = "lock:transfer:" + customerId;
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", Duration.ofSeconds(120));
        if (!Boolean.TRUE.equals(locked)) {
            // 锁被占用说明另一线程正在处理同一客户，抛异常让 TransferWorker 重试而非静默跳过
            log.info("客户 {} 继承正在处理中（锁占用），稍后重试", customerId);
            throw new LockContentionException("客户 " + customerId + " 继承锁占用，稍后重试");
        }
        // 将锁释放注册到事务完成后：@Transactional 在方法返回后才提交，
        // 若在 finally 中提前释放，其他线程可能在事务可见前查到空记录并重复发起转移。
        // syncActive 标记用于兜底：若事务同步未激活（极端场景），finally 直接释放锁，
        // 避免 120s TTL 期间阻塞同一客户的所有转移请求。
        boolean syncActive = false;
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            redisTemplate.delete(lockKey);
                        }
                    });
                syncActive = true;
            }
        // ---- 去重：避免同一客户重复发起继承 ----
        // 只排除进行中/已完成的转移（pending_confirm / confirmed），
        // timeout / rejected / api_failed / retry_limit 的客户允许重新转移。
        // api_failed 的重试由 retryFailedTransfers() 独立控制（最多 3 次），不经过 Stream。
        List<CustomerTransfer.TransferStatus> dedupStatuses = List.of(
            CustomerTransfer.TransferStatus.pending_confirm,
            CustomerTransfer.TransferStatus.confirmed);
        if (transferRepo.existsByCustomerIdAndStatusIn(customerId, dedupStatuses)) {
            log.info("客户 {} 已有进行中/已完成继承记录，跳过", customerId);
            return;
        }

        // ---- 冷却期：7 天内 timeout/rejected/retry_limit 的客户不重转 ----
        // 防止僵尸客户陷入 "发起 → 超时 → 再发起" 的死循环，浪费 API 配额
        if (transferRepo.existsRecentTerminalByCustomerId(customerId,
                LocalDateTime.now().minus(TRANSFER_COOLDOWN))) {
            log.info("客户 {} 在冷却期内（{} 天内有 terminal 记录），跳过",
                customerId, TRANSFER_COOLDOWN.toDays());
            return;
        }

        // 根据 state（schoolId）查找活码，无对应活码则跳过
        QrCode qr = qrCodeRepo.findBySchoolId(state).orElse(null);
        if (qr == null) {
            log.warn("继承发起跳过: 找不到对应活码, state={}, customerId={}", state, customerId);
            return;
        }

        // 查找服务老师时包含 active/full（full 仍可接收转移），仅排除 removed
        List<QrAgent> agents = qrAgentRepo.findByQrCodeId(qr.getId()).stream()
            .filter(a -> a.getStatus() != QrAgent.AgentStatus.removed)
            .toList();

        // 确定目标服务老师：优先使用传入的 toUserid（若仍为活跃 service/dual），否则自动查找
        QrAgent serviceAgent = null;
        if (toUserid != null) {
            final String finalToUserid = toUserid;
            serviceAgent = agents.stream()
                .filter(a -> (a.getRole() == QrAgent.AgentRole.service
                           || a.getRole() == QrAgent.AgentRole.dual)
                           && a.getAgentUserid().equals(finalToUserid))
                .findFirst().orElse(null);
            if (serviceAgent == null) {
                log.info("指定的服务老师 {} 已不是活跃 service/dual，改为自动查找", toUserid);
            }
        }
        if (serviceAgent == null) {
            serviceAgent = agents.stream()
                .filter(a -> a.getRole() == QrAgent.AgentRole.service
                          || a.getRole() == QrAgent.AgentRole.dual)
                .findFirst().orElse(null);
        }

        if (serviceAgent == null) {
            log.info("活码 {} 未配置服务老师，跳过继承", qr.getId());
            return;
        }

        // 跳过自己转自己（dual 角色场景）
        if (serviceAgent.getAgentUserid().equals(fromUserid)) {
            log.info("服务老师与接待员为同一人 (dual)，跳过继承: userid={}", fromUserid);
            return;
        }

        // 校验服务老师企微状态：异常时跳过转移并记录告警
        Agent svcAgentRecord = agentRepo.findById(serviceAgent.getAgentUserid()).orElse(null);
        Employee svcEmpRecord = employeeRepo.findByUserid(serviceAgent.getAgentUserid()).orElse(null);
        String anomaly = QrCodeService.getAnomalyLabel(svcAgentRecord, svcEmpRecord);
        if (anomaly != null) {
            log.warn("服务老师 {} 状态异常 ({}), 跳过转移: customerId={}",
                serviceAgent.getAgentUserid(), anomaly, customerId);
            transferRepo.save(CustomerTransfer.builder()
                .customerId(customerId)
                .fromUserid(fromUserid)
                .toUserid(serviceAgent.getAgentUserid())
                .qrCodeId(qr.getId())
                .transferTime(LocalDateTime.now())
                .status(CustomerTransfer.TransferStatus.api_failed)
                .failReason("目标服务老师异常: " + anomaly)
                .build());
            return;
        }

        try {
                // 调企微 API 发起继承（含可配置的转接成功通知消息，支持 {{teacher_name}} 变量）
                String transferSuccessMsg = fillTransferSuccessMsg(
                    resolveTransferSuccessMsg(qr), serviceAgent.getAgentUserid());
                wecomApi.transferCustomer(
                    fromUserid, serviceAgent.getAgentUserid(), externalUserid,
                    transferSuccessMsg);
                // parseAndCheck 保证 errcode=0

                // 检查客户是否已填写收集表单（影响后续 A/B 欢迎语分支）
                Boolean formFilled = checkFormFilled(customerId);

                CustomerTransfer transfer = CustomerTransfer.builder()
                    .customerId(customerId)
                    .fromUserid(fromUserid)
                    .toUserid(serviceAgent.getAgentUserid())
                    .qrCodeId(qr.getId())
                    .transferTime(LocalDateTime.now())
                    .retryCount(0)
                    .formFilledAtTransfer(formFilled)
                    .status(CustomerTransfer.TransferStatus.pending_confirm)
                    .build();

                transferRepo.save(transfer);
                log.info("继承发起成功: customer={}, from={}, to={}",
                    externalUserid, fromUserid, serviceAgent.getAgentUserid());

            } catch (WecomApiException e) {
                // 可重试异常（token 过期/限流/网络错误）→ 往上抛给 TransferWorker，
                // 由其 classifyWecomError 决定具体重试策略（刷新 token / 等待退避 / DLQ）
                // 例外：45035（操作冲突）在转移上下文中为终端错误——表示客户已有进行中的转移
                if (e.getErrcode() != WecomErrorCodes.TRANSFER_CONFLICT
                    && (e instanceof WecomTokenExpiredException
                        || e instanceof WecomRateLimitException
                        || e instanceof WecomTransientException)) {
                    throw e;
                }

                // 永久性错误 → 落库，不阻塞主流程
                // 以下错误码重试无法修复，直接标记 retry_limit：
                //   - 84061: 客户已不是好友 → 无法发起继承
                //   - 84096: 客户无法发起在职继承
                //   - 84097: 接替成员客户数已达上限
                //   - 84100: 已有正在继承的员工（极端竞态）
                //   - 84073: 客户已删除服务人员
                //   - 45035: 操作冲突（客户已有进行中的转移）
                // 注意：40205（票据过期）不再视为终端错误 —— ticket 间歇性恢复后重试即可成功
                boolean terminal = e.getErrcode() == WecomErrorCodes.NOT_EXTERNAL_CONTACT
                    || e.getErrcode() == WecomErrorCodes.TRANSFER_NOT_AVAILABLE
                    || e.getErrcode() == WecomErrorCodes.TRANSFER_LIMIT_EXCEEDED
                    || e.getErrcode() == WecomErrorCodes.TRANSFER_PENDING_EXISTS
                    || e.getErrcode() == WecomErrorCodes.DELETED_BY_USER
                    || e.getErrcode() == WecomErrorCodes.TRANSFER_CONFLICT;
                CustomerTransfer.TransferStatus failStatus = terminal
                    ? CustomerTransfer.TransferStatus.retry_limit
                    : CustomerTransfer.TransferStatus.api_failed;
                String failReason;
                if (e.getErrcode() == WecomErrorCodes.NOT_EXTERNAL_CONTACT) {
                    failReason = "客户已不是好友(errcode=84061)，无法发起继承";
                } else if (e.getErrcode() == WecomErrorCodes.TICKET_EXPIRED) {
                    failReason = "接管员工企微票据过期(errcode=40205)，需重新登录企微并微信授权";
                } else if (e.getErrcode() == WecomErrorCodes.TRANSFER_NOT_AVAILABLE) {
                    failReason = "客户无法发起在职继承(errcode=84096)";
                } else if (e.getErrcode() == WecomErrorCodes.TRANSFER_LIMIT_EXCEEDED) {
                    failReason = "接替成员客户数已达上限(errcode=84097)";
                } else if (e.getErrcode() == WecomErrorCodes.TRANSFER_PENDING_EXISTS) {
                    failReason = "已有正在继承的员工(errcode=84100)";
                } else if (e.getErrcode() == WecomErrorCodes.DELETED_BY_USER) {
                    failReason = "客户已删除服务人员(errcode=84073)，无法发起继承";
                } else if (e.getErrcode() == WecomErrorCodes.TRANSFER_CONFLICT) {
                    failReason = "操作冲突(errcode=45035)，客户已有进行中的转移";
                } else {
                    failReason = "errcode=" + e.getErrcode() + " " + e.getErrmsg();
                }

                transferRepo.save(CustomerTransfer.builder()
                    .customerId(customerId)
                    .fromUserid(fromUserid)
                    .toUserid(serviceAgent.getAgentUserid())
                    .qrCodeId(qr.getId())
                    .transferTime(LocalDateTime.now())
                    .status(failStatus)
                    .failReason(failReason)
                    .build());

                if (terminal) {
                    log.warn("继承发起跳过: 终端错误, customerId={}, from={}, external={}, errcode={}",
                        customerId, fromUserid, externalUserid, e.getErrcode());
                } else {
                    log.error("继承发起失败: errcode={}, errmsg={}", e.getErrcode(), e.getErrmsg());
                }

            } catch (Exception e) {
                log.error("继承发起异常: external={}", externalUserid, e);
                // 捕获所有异常，记录失败记录但不影响主流程（添加客户）
                transferRepo.save(CustomerTransfer.builder()
                    .customerId(customerId)
                    .fromUserid(fromUserid)
                    .toUserid(serviceAgent.getAgentUserid())
                    .qrCodeId(qr.getId())
                    .transferTime(LocalDateTime.now())
                    .status(CustomerTransfer.TransferStatus.api_failed)
                    .failReason(e.getMessage())
                    .build());
            }
        } finally {
            // 安全网：若事务同步未激活（极端场景，如 AOP 自调用绕过了代理），
            // 直接释放锁，避免 120s TTL 期间阻塞同一客户的所有转移请求
            if (!syncActive) {
                redisTemplate.delete(lockKey);
            }
        }
    }

    /**
     * 检查客户是否已填写收集表单。
     * <p>
     * 通过查询 {@link CustomerTag} 表中是否存在来源为 {@link CustomerTag.TagSource#form} 的标签来判断。
     * 该结果影响后续欢迎语走已填写（A 分支）还是未填写（B 分支）。
     * </p>
     *
     * @param customerId 客户 ID
     * @return 已填写返回 {@code true}，未填写返回 {@code false}；无标签记录返回 {@code false}
     */
    private Boolean checkFormFilled(Long customerId) {
        List<CustomerTag> tags = customerTagRepo.findByCustomerId(customerId);
        return tags.stream().anyMatch(t -> t.getSource() == CustomerTag.TagSource.form);
    }

    /**
     * 追踪在职继承结果（由定时任务周期性调用）。
     * <p>
     * 查询所有状态为 {@link CustomerTransfer.TransferStatus#pending_confirm} 且重试次数 &lt; 48 的记录，
     * 逐一调用企微 API {@code get_transfer_result} 获取最新状态，按企微返回的状态码处理：
     * <ul>
     *   <li><b>1 接替完毕</b> → {@link CustomerTransfer.TransferStatus#confirmed}，
     *       并触发 {@link #sendTransferGreeting} 发送交接欢迎语</li>
     *   <li><b>2 等待接替</b>（客户未确认）→ 累加重试次数继续轮询，超 24h 标记 timeout</li>
     *   <li><b>3 客户拒绝</b> / <b>4 接替成员达上限</b> → {@link CustomerTransfer.TransferStatus#rejected}</li>
     *   <li><b>5 无接替记录</b> / 未知码 → 累加重试次数，超时/耗尽则终止</li>
     * </ul>
     * API 参考: https://developer.work.weixin.qq.com/document/path/96327
     * </p>
     */
    /**
     * 注意：此方法不标注 @Transactional，每条记录独立事务提交，避免长事务超时。
     * 每条记录的 save()/find*() 由 Spring Data JPA 默认的事务机制各自包裹，
     * 防止大量 pending_confirm 记录在单次 30s 事务中超时。
     */
    public List<Long> trackResults() {
        List<Long> newlyConfirmed = new ArrayList<>();
        List<CustomerTransfer> pendings = transferRepo
            .findByStatusAndPollCountLessThan(CustomerTransfer.TransferStatus.pending_confirm, 48);

        for (CustomerTransfer t : pendings) {
            try {
                // 获取客户的 external_userid 用于在 API 返回数组中匹配
                String externalUserid = customerRepo.findById(t.getCustomerId())
                    .map(Customer::getExternalUserid).orElse("");

                // 调企微 API 查询继承结果（不再传 external_userid 给 API，
                // 改为遍历返回的 customer 数组按 external_userid 匹配目标客户）
                JsonNode result = wecomApi.getTransferResult(
                    t.getFromUserid(), t.getToUserid(), externalUserid);
                int apiStatus = findCustomerStatus(result, externalUserid);

                // 第一页未找到目标客户时，翻页查找（最多 5 页）
                String cursor = (apiStatus == -1 && result.has("next_cursor"))
                    ? result.get("next_cursor").asText() : null;
                int pageCount = 0;
                while (apiStatus == -1 && cursor != null && !cursor.isEmpty() && pageCount < 5) {
                    result = wecomApi.getTransferResult(
                        t.getFromUserid(), t.getToUserid(), externalUserid, cursor);
                    apiStatus = findCustomerStatus(result, externalUserid);
                    cursor = (apiStatus == -1 && result.has("next_cursor"))
                        ? result.get("next_cursor").asText() : null;
                    pageCount++;
                }

                // 统一的超时基准时间
                LocalDateTime refTime = t.getTransferTime() != null
                    ? t.getTransferTime()
                    : (t.getCreatedAt() != null ? t.getCreatedAt() : LocalDateTime.now());
                boolean expired = refTime.plus(TRANSFER_TIMEOUT).isBefore(LocalDateTime.now());

                switch (apiStatus) {
                    case 1: // 接替完毕 → confirmed
                        t.setStatus(CustomerTransfer.TransferStatus.confirmed);
                        t.setConfirmTime(LocalDateTime.now());
                        newlyConfirmed.add(t.getId());
                        log.info("转移已确认: transferId={}, customerId={}, pollCount={}",
                            t.getId(), t.getCustomerId(), t.getPollCount());
                        break;
                    case 2: // 等待接替（客户尚未确认）
                        t.setPollCount(t.getPollCount() + 1);
                        if (expired) {
                            // 企微静默 24h 后自动完成转移，标记 confirmed
                            t.setStatus(CustomerTransfer.TransferStatus.confirmed);
                            t.setConfirmTime(refTime.plus(TRANSFER_TIMEOUT));
                            newlyConfirmed.add(t.getId());
                            log.info("转移超时自动确认(API status=2): transferId={}, customerId={}",
                                t.getId(), t.getCustomerId());
                        }
                        break;
                    case 3: // 客户拒绝 → rejected
                    case 4: // 接替成员客户数达上限（终态）
                        t.setStatus(CustomerTransfer.TransferStatus.rejected);
                        t.setConfirmTime(LocalDateTime.now());
                        if (apiStatus == 4) {
                            t.setFailReason("接替成员客户数已达上限");
                        }
                        break;
                    default:
                        // 状态码 5（无此转移记录）/ -1（未找到目标客户）/ 未知码
                        t.setPollCount(t.getPollCount() + 1);
                        if (expired) {
                            // 企微静默 24h 后自动完成，API 可能已不返回该记录
                            t.setStatus(CustomerTransfer.TransferStatus.confirmed);
                            t.setConfirmTime(refTime.plus(TRANSFER_TIMEOUT));
                            t.setFailReason("企微24h自动完成(API未找到记录)");
                            newlyConfirmed.add(t.getId());
                            log.info("转移超时自动确认(API未找到): transferId={}, customerId={}",
                                t.getId(), t.getCustomerId());
                        } else {
                            log.debug("getTransferResult 未找到目标客户: transferId={}, apiStatus={}",
                                t.getId(), apiStatus);
                        }
                }
                transferRepo.save(t);
            } catch (WecomApiException e) {
                t.setPollCount(t.getPollCount() + 1);
                LocalDateTime refTime = t.getTransferTime() != null
                    ? t.getTransferTime()
                    : (t.getCreatedAt() != null ? t.getCreatedAt() : LocalDateTime.now());
                if (refTime.plus(TRANSFER_TIMEOUT).isBefore(LocalDateTime.now())) {
                    // 超时且 API 异常 → 企微侧大概率已完成
                    t.setStatus(CustomerTransfer.TransferStatus.confirmed);
                    t.setConfirmTime(refTime.plus(TRANSFER_TIMEOUT));
                    t.setFailReason("企微24h自动完成(API异常:" + e.getErrmsg() + ")");
                    newlyConfirmed.add(t.getId());
                    log.info("转移超时自动确认(API异常): transferId={}, errcode={}",
                        t.getId(), e.getErrcode());
                }
                transferRepo.save(t);
                log.error("追踪继承结果 API 失败: transferId={}, errcode={}, errmsg={}",
                    t.getId(), e.getErrcode(), e.getErrmsg());
            } catch (Exception e) {
                t.setPollCount(t.getPollCount() + 1);
                LocalDateTime refTime = t.getTransferTime() != null
                    ? t.getTransferTime()
                    : (t.getCreatedAt() != null ? t.getCreatedAt() : LocalDateTime.now());
                if (refTime.plus(TRANSFER_TIMEOUT).isBefore(LocalDateTime.now())) {
                    t.setStatus(CustomerTransfer.TransferStatus.confirmed);
                    t.setConfirmTime(refTime.plus(TRANSFER_TIMEOUT));
                    t.setFailReason("企微24h自动完成(异常:" + e.getMessage() + ")");
                    newlyConfirmed.add(t.getId());
                }
                transferRepo.save(t);
                log.error("追踪继承结果异常: transferId={}", t.getId(), e);
            }
        }

        // 安全网：历史遗留 pollCount ≥48 的 pending_confirm 记录
        // 修复前这些会被标记 retry_limit，修复后按超时逻辑处理
        List<CustomerTransfer> exhausted = transferRepo
            .findByStatusAndPollCountGreaterThanEqual(
                CustomerTransfer.TransferStatus.pending_confirm, 48);
        for (CustomerTransfer t : exhausted) {
            LocalDateTime refTime = t.getTransferTime() != null
                ? t.getTransferTime()
                : (t.getCreatedAt() != null ? t.getCreatedAt() : LocalDateTime.now());
            if (refTime.plus(TRANSFER_TIMEOUT).isBefore(LocalDateTime.now())) {
                // 已超 24h → 企微应已完成自动转移
                // confirmTime 取实际完成时间（transferTime+24h）而非 now，
                // 避免欢迎语窗口误判：老记录的实际完成时间远早于 now，
                // 用 now 会导致 sendGreetingsForNewlyConfirmed 跳不过旧记录
                t.setStatus(CustomerTransfer.TransferStatus.confirmed);
                t.setConfirmTime(refTime.plus(TRANSFER_TIMEOUT));
                t.setFailReason("企微24h自动完成(历史兜底)");
                newlyConfirmed.add(t.getId());
                log.info("历史转移自动确认: id={}, customerId={}", t.getId(), t.getCustomerId());
            }
            // 未超 24h 但 pollCount ≥48 的情况极罕见，保留 retry_limit 作为安全阀
            transferRepo.save(t);
        }
        if (!exhausted.isEmpty()) {
            log.info("历史安全网处理: {} 条记录, confirmed={}",
                exhausted.size(),
                exhausted.stream().filter(t -> t.getStatus() == CustomerTransfer.TransferStatus.confirmed).count());
        }
        return newlyConfirmed;
    }

    /**
     * 在 get_transfer_result 返回的 customer 数组中匹配目标客户。
     *
     * @param result         企微 API 返回的 JsonNode
     * @param externalUserid 目标客户的 external_userid
     * @return 匹配到的 status 值，未找到返回 -1
     */
    private int findCustomerStatus(JsonNode result, String externalUserid) {
        if (result.has("customer") && result.get("customer").isArray()) {
            for (JsonNode c : result.get("customer")) {
                if (c.has("external_userid")
                    && externalUserid.equals(c.get("external_userid").asText())) {
                    return c.has("status") ? c.get("status").asInt(-1) : -1;
                }
            }
        }
        return -1;
    }

    /**
     * 发送新确认转移的交接欢迎语。
     * <p>
     * 从 {@link #trackResults()} 返回后单独调用，欢迎语发送（企微 API 网络 I/O）
     * 在独立的处理周期内完成，避免阻塞轮询主流程。
     * </p>
     * 每条记录独立 try-catch，单条失败不影响其他。
     * </p>
     *
     * @param transferIds 新确认的转移记录 ID 列表
     */
    public void sendGreetingsForNewlyConfirmed(List<Long> transferIds) {
        if (transferIds == null || transferIds.isEmpty()) return;
        // 超过 24h 确认的旧记录不发欢迎语，避免对历史客户造成骚扰
        LocalDateTime greetingCutoff = LocalDateTime.now().minusHours(24);
        int sentCount = 0;
        int skippedOld = 0;
        for (Long id : transferIds) {
            try {
                CustomerTransfer transfer = transferRepo.findById(id).orElse(null);
                if (transfer == null) continue;
                if (transfer.getConfirmTime() != null
                    && transfer.getConfirmTime().isBefore(greetingCutoff)) {
                    // 欢迎语发送窗口已过（确认时间超过 24h），跳过
                    skippedOld++;
                    continue;
                }
                sendTransferGreeting(transfer);
                sentCount++;
                // 限速保护：每 10 条间隔 200ms，避免冲击企微消息 API
                if (sentCount % 10 == 0) {
                    try { Thread.sleep(200); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            } catch (Exception e) {
                log.error("发送交接欢迎语失败: transferId={}", id, e);
            }
        }
        if (skippedOld > 0) {
            log.info("跳过 {} 条超 24h 旧记录的欢迎语发送", skippedOld);
        }
    }

    /**
     * 重试 API 调用失败的转移记录（由定时任务周期性调用）。
     * <p>
     * 查询所有状态为 {@link CustomerTransfer.TransferStatus#api_failed} 且重试次数 < 3 的记录，
     * 重新调用企微 {@code transfer_customer} API。成功后状态变为 pending_confirm，
     * 失败则累加重试次数，达到上限（≥3）标记为 retry_limit。
     * </p>
     * <p>
     * 注意：此方法不标注 @Transactional，每条失败记录独立事务提交，避免长事务超时。
     * 每条记录的 save()/exists*() 由 Spring Data JPA 默认的事务机制各自包裹，
     * 防止大量失败记录在单次 30s 事务中超时。
     * </p>
     */
    public void retryFailedTransfers() {
        List<CustomerTransfer> failed = transferRepo
            .findByStatusAndRetryCountLessThan(CustomerTransfer.TransferStatus.api_failed, 3);

        for (CustomerTransfer t : failed) {
            // ---- Redis 分布式锁：防止同客户两条 api_failed 记录并发重试 ----
            String retryLockKey = "lock:transfer:" + t.getCustomerId();
            Boolean retryLocked = redisTemplate.opsForValue()
                .setIfAbsent(retryLockKey, "1", Duration.ofSeconds(120));
            if (!Boolean.TRUE.equals(retryLocked)) {
                // 锁占用，跳过本次重试（下次周期再试）
                continue;
            }
            try {
                // 去重：检查是否已有进行中/已完成的转移（可能在 api_failed 期间由其他路径发起）
                if (transferRepo.existsByCustomerIdAndStatusIn(t.getCustomerId(),
                        List.of(CustomerTransfer.TransferStatus.pending_confirm,
                                CustomerTransfer.TransferStatus.confirmed))) {
                    t.setStatus(CustomerTransfer.TransferStatus.retry_limit);
                    t.setFailReason("客户已有进行中的转移记录，放弃重试");
                    transferRepo.save(t);
                    continue;
                }
                // 84061/84096/84097/84100/84073/45035 为永久性错误，重试无效，直接标记终端
                // 40205 不在此列 —— ticket 可间歇恢复，允许重试
                if (t.getFailReason() != null
                    && (t.getFailReason().contains("errcode=84061")
                        || t.getFailReason().contains("errcode=84096")
                        || t.getFailReason().contains("errcode=84097")
                        || t.getFailReason().contains("errcode=84100")
                        || t.getFailReason().contains("errcode=84073")
                        || t.getFailReason().contains("errcode=45035"))) {
                    t.setStatus(CustomerTransfer.TransferStatus.retry_limit);
                    if (t.getFailReason().contains("errcode=84061")) {
                        t.setFailReason("客户已不是好友(errcode=84061)，无法发起继承");
                    } else if (t.getFailReason().contains("errcode=84096")) {
                        t.setFailReason("客户无法发起在职继承(errcode=84096)");
                    } else if (t.getFailReason().contains("errcode=84097")) {
                        t.setFailReason("接替成员客户数已达上限(errcode=84097)");
                    } else if (t.getFailReason().contains("errcode=84100")) {
                        t.setFailReason("已有正在继承的员工(errcode=84100)");
                    } else if (t.getFailReason().contains("errcode=84073")) {
                        t.setFailReason("客户已删除服务人员(errcode=84073)，无法发起继承");
                    } else {
                        t.setFailReason("操作冲突(errcode=45035)，客户已有进行中的转移");
                    }
                    transferRepo.save(t);
                    log.warn("api_failed 跳过重试: transferId={}, reason={}",
                        t.getId(), t.getFailReason());
                    continue;
                }
                Customer customer = customerRepo.findById(t.getCustomerId()).orElse(null);
                if (customer == null) {
                    t.setStatus(CustomerTransfer.TransferStatus.retry_limit);
                    t.setFailReason("客户不存在");
                    transferRepo.save(t);
                    continue;
                }
                wecomApi.transferCustomer(t.getFromUserid(), t.getToUserid(),
                    customer.getExternalUserid(),
                    fillTransferSuccessMsg(resolveRetryTransferSuccessMsg(t), t.getToUserid()));
                t.setStatus(CustomerTransfer.TransferStatus.pending_confirm);
                t.setRetryCount(t.getRetryCount() + 1);
                transferRepo.save(t);
                log.info("api_failed 重试成功: transferId={}", t.getId());
            } catch (WecomApiException e) {
                // 84061/84096/84097/84100/84073/45035 为永久性错误，不累加重试次数，直接标记终端
                // 40205 不在此列 —— ticket 可间歇恢复，累加重试次数走正常重试流程
                if (e.getErrcode() == WecomErrorCodes.NOT_EXTERNAL_CONTACT
                    || e.getErrcode() == WecomErrorCodes.TRANSFER_NOT_AVAILABLE
                    || e.getErrcode() == WecomErrorCodes.TRANSFER_LIMIT_EXCEEDED
                    || e.getErrcode() == WecomErrorCodes.TRANSFER_PENDING_EXISTS
                    || e.getErrcode() == WecomErrorCodes.DELETED_BY_USER
                    || e.getErrcode() == WecomErrorCodes.TRANSFER_CONFLICT) {
                    t.setStatus(CustomerTransfer.TransferStatus.retry_limit);
                    if (e.getErrcode() == WecomErrorCodes.NOT_EXTERNAL_CONTACT) {
                        t.setFailReason("客户已不是好友(errcode=84061)，无法发起继承");
                    } else if (e.getErrcode() == WecomErrorCodes.TRANSFER_NOT_AVAILABLE) {
                        t.setFailReason("客户无法发起在职继承(errcode=84096)");
                    } else if (e.getErrcode() == WecomErrorCodes.TRANSFER_LIMIT_EXCEEDED) {
                        t.setFailReason("接替成员客户数已达上限(errcode=84097)");
                    } else if (e.getErrcode() == WecomErrorCodes.TRANSFER_PENDING_EXISTS) {
                        t.setFailReason("已有正在继承的员工(errcode=84100)");
                    } else if (e.getErrcode() == WecomErrorCodes.DELETED_BY_USER) {
                        t.setFailReason("客户已删除服务人员(errcode=84073)，无法发起继承");
                    } else {
                        t.setFailReason("操作冲突(errcode=45035)，客户已有进行中的转移");
                    }
                    transferRepo.save(t);
                    log.warn("api_failed 重试终止: {}, transferId={}",
                        resolveTerminalReason(e.getErrcode()), t.getId());
                } else {
                    t.setRetryCount(t.getRetryCount() + 1);
                    t.setFailReason("重试失败: errcode=" + e.getErrcode() + " " + e.getErrmsg());
                    if (t.getRetryCount() >= 3) {
                        t.setStatus(CustomerTransfer.TransferStatus.retry_limit);
                    }
                    transferRepo.save(t);
                    log.error("api_failed 重试失败: transferId={}, errcode={}", t.getId(), e.getErrcode());
                }
            } catch (Exception e) {
                t.setRetryCount(t.getRetryCount() + 1);
                if (t.getRetryCount() >= 3) {
                    t.setStatus(CustomerTransfer.TransferStatus.retry_limit);
                    t.setFailReason("重试耗尽: " + e.getMessage());
                }
                transferRepo.save(t);
                log.error("api_failed 重试异常: transferId={}", t.getId(), e);
            } finally {
                // 每条记录处理完成后立即释放 Redis 锁（不再依赖外层事务同步），
                // 因为每条记录已在各自的 save() 事务中提交，锁可以安全释放
                redisTemplate.delete(retryLockKey);
            }
        }
        if (!failed.isEmpty()) {
            log.info("api_failed 重试完成: 处理 {} 条", failed.size());
        }

        // 安全网：retryCount ≥3 但仍为 api_failed 的记录（极端崩溃场景兜底）
        List<CustomerTransfer> exhaustedRetries = transferRepo
            .findByStatusAndRetryCountGreaterThanEqual(
                CustomerTransfer.TransferStatus.api_failed, 3);
        for (CustomerTransfer t : exhaustedRetries) {
            t.setStatus(CustomerTransfer.TransferStatus.retry_limit);
            if (t.getFailReason() == null || t.getFailReason().isBlank()) {
                t.setFailReason("重试耗尽(安全网兜底)");
            }
            transferRepo.save(t);
            log.warn("api_failed 安全网兜底: transferId={}, retryCount={}", t.getId(), t.getRetryCount());
        }
        if (!exhaustedRetries.isEmpty()) {
            log.info("api_failed 安全网处理: {} 条 → retry_limit", exhaustedRetries.size());
        }

        // 无条件检查 retry_limit 累积（覆盖所有路径：initiate 终端错误 + 重试耗尽 + 轮询耗尽）
        checkRetryLimitAccumulation();
    }

    /**
     * 检查服务老师/双角色的 retry_limit 累积情况，达到阈值时告警。
     * <p>
     * retry_limit 的三大来源（按常见程度排序）：
     * <ol>
     *   <li>{@code initiate()} 终端错误 — errcode=40205 企微票据过期 / 84097 客户数上限</li>
     *   <li>{@code retryFailedTransfers()} 重试耗尽 — API 调用经 3 次重试仍失败</li>
     *   <li>{@code trackResults()} 轮询耗尽 — 仅未满 24h 但 pollCount 已 ≥48 的极端情况</li>
     * </ol>
     * 注意：24h 超时的 pending_confirm 记录在 trackResults 中已直接标记 confirmed，
     * 不再落入 retry_limit，因此告警主要针对真实的 API 层问题。
     * </p>
     *
     * <p><b>三层防护：</b>
     * <ol>
     *   <li>子查询匹配 service/dual 角色，避免多次 JOIN 放大 COUNT</li>
     *   <li>7 天时间窗口，防止历史问题已修复后仍重复告警</li>
     *   <li>24h 去重：同一老师相同告警类型未处理期间不再重复创建</li>
     * </ol>
     * </p>
     */
    private void checkRetryLimitAccumulation() {
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        List<Object[]> results = transferRepo.findRetryLimitTeachers(since);

        if (results.isEmpty()) {
            return;
        }

        LocalDateTime dedupSince = LocalDateTime.now().minusHours(24);

        for (Object[] row : results) {
            String toUserid = (String) row[0];
            long count = ((Number) row[1]).longValue();

            // 24h 去重：已有未处理同类告警则跳过
            List<AgentAlert> existing = alertRepo
                .findByAgentUseridAndAlertTypeAndStatusAndCreatedAtAfter(
                    toUserid, "transfer_retry_exhausted_svc",
                    AgentAlert.AlertStatus.open, dedupSince);
            if (!existing.isEmpty()) {
                log.info("服务老师 {} 24h 内已有未处理告警，跳过重复告警", toUserid);
                continue;
            }

            // 不同原因给不同的告警文案，帮助运维快速判断
            String alertType = "transfer_retry_exhausted_svc";
            String message = String.format(
                "服务老师/双角色 %s 近 7 天有 %d 条继承转移已达重试上限。"
                + "常见原因：企微票据过期需重新登录(40205)、客户数达上限(84097)、操作冲突(45035)。"
                + "请检查该老师企微状态及客户配额。",
                toUserid, count);

            alertService.createAlert(toUserid, alertType,
                AgentAlert.AlertSeverity.high, message,
                AgentAlert.AutoAction.none, null);
        }
    }

    /**
     * 补偿发送失败的交接欢迎语（由定时任务周期性调用）。
     * <p>
     * 在 {@link #trackResults()} 确认转移时，{@link #sendTransferGreeting}
     * 若发送失败不会重试。本方法扫描最近 24 小时内已确认但欢迎语未发送的记录，
     * 逐一补发。超过 24 小时的记录认为发送窗口已过，不再重试。
     * </p>
     */
    public void retryFailedGreetings() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        List<CustomerTransfer> missed = transferRepo
            .findByStatusAndGreetingSentAndConfirmTimeAfter(
                CustomerTransfer.TransferStatus.confirmed, false, cutoff);

        for (CustomerTransfer t : missed) {
            try {
                sendTransferGreeting(t);
                if (Boolean.TRUE.equals(t.getGreetingSent())) {
                    log.info("欢迎语补发成功: transferId={}", t.getId());
                }
            } catch (Exception e) {
                log.warn("欢迎语补发失败: transferId={}, err={}", t.getId(), e.getMessage());
            }
        }
        if (!missed.isEmpty()) {
            log.info("欢迎语补发完成: 处理 {} 条, 窗口=24h", missed.size());
        }
    }

    /**
     * 修复因 field_data 键名与模板占位符不匹配导致的损坏备注。
     * <p>从客户最近一次表单提交的 field_data 重新构建正确的备注并推送到企微。</p>
     *
     * @return 修复的客户数
     */
    public Map<String, Object> repairBrokenRemarks() {
        List<CustomerTransfer> confirmed = transferRepo
            .findConfirmedWithFormSubmission();
        if (confirmed.isEmpty()) return Map.of("repaired", 0, "samples", List.of());

        int repaired = 0;
        List<Map<String, String>> samples = new ArrayList<>();
        final int MAX_SAMPLES = 5;

        for (CustomerTransfer ct : confirmed) {
            try {
                Customer customer = customerRepo.findById(ct.getCustomerId()).orElse(null);
                if (customer == null) continue;

                QrCode qr = ct.getQrCodeId() != null
                    ? qrCodeRepo.findById(ct.getQrCodeId()).orElse(null) : null;

                Map<String, String> fields = extractFormFields(ct.getCustomerId());
                if (fields.isEmpty()) continue;

                // 构建模板变量（与 sendTransferGreeting 一致）
                Map<String, String> vars = new LinkedHashMap<>();
                vars.put("parent_name", customer.getName());
                vars.put("school_name", qr != null && qr.getSchoolName() != null
                    ? qr.getSchoolName() : "");
                vars.put("teacher_name", getTeacherName(ct.getToUserid()));
                vars.putAll(fields);

                // 取原始模板
                String template = qr != null && qr.getTransferFilledNote() != null
                    && !qr.getTransferFilledNote().isBlank()
                    ? qr.getTransferFilledNote()
                    : "{{grade}}{{class}} | 孩子：{{child_name}} | 来源：{{school_name}}";

                // 重建"修复前"备注（只替换能匹配的变量，不清理残留占位符）
                String before = template;
                for (Map.Entry<String, String> entry : vars.entrySet()) {
                    before = before.replace("{{" + entry.getKey() + "}}",
                        entry.getValue() != null ? entry.getValue() : "");
                }

                // 清空备注，企微将显示客户网名
                wecomApi.updateRemark(ct.getToUserid(),
                    customer.getExternalUserid(), "");
                repaired++;

                if (samples.size() < MAX_SAMPLES) {
                    samples.add(Map.of("before", before, "after", "（显示客户网名）"));
                }

                if (repaired % 10 == 0) {
                    try { Thread.sleep(200); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            } catch (Exception e) {
                log.warn("备注修复失败: customerId={}, err={}", ct.getCustomerId(), e.getMessage());
            }
        }
        log.info("备注修复完成: 共 {} 条", repaired);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repaired", repaired);
        result.put("samples", samples);
        return result;
    }

    /**
     * 从客户最近一次表单提交中提取字段数据，返回字段名→值的映射。
     * <p>
     * 用于填充交接欢迎语模板中的 {@code {{field_name}}} 占位符。
     * 示例：表单提交的 field_data 为 {@code {"grade":"三年级","child_name":"张三"}}
     * → 返回 Map 含 {@code grade→"三年级", child_name→"张三"}。
     * </p>
     *
     * @param customerId 客户 ID
     * @return 字段名→值的映射，无提交记录或解析失败时返回空 Map
     */
    private Map<String, String> extractFormFields(Long customerId) {
        Map<String, String> fields = new LinkedHashMap<>();
        List<FormSubmission> submissions = formSubmissionRepo
            .findByCustomerIdOrderBySubmittedAtDesc(customerId);
        if (submissions.isEmpty()) return fields;
        try {
            JsonNode data = objectMapper.readTree(submissions.get(0).getFieldData());
            data.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                fields.put(entry.getKey(), value.isTextual()
                    ? value.asText() : value.toString());
            });
        } catch (Exception e) {
            log.warn("解析表单提交数据失败: submissionId={}", submissions.get(0).getId(), e);
        }
        return fields;
    }

    /**
     * 发送继承后的交接欢迎语（A/B 分支）。
     * <p>
     * 根据 {@link CustomerTransfer#formFilledAtTransfer} 的值走不同分支：
     * <ul>
     *   <li><b>路径 A（已填写）</b>：发送带备注和交接语的消息，
     *       模板变量含家长名、学校名、老师名以及从表单提交数据中提取的字段</li>
     *   <li><b>路径 B（未填写）</b>：发送提醒填写消息，附带收集表单链接</li>
     * </ul>
     * 欢迎语内容从活码的 {@code welcome_config} JSON 中读取，
     * 需先检查 {@code transfer_greeting_enabled} 开关是否开启。
     * </p>
     *
     * @param transfer 客户转移记录，包含客户 ID、活码 ID、formFilledAtTransfer 等字段
     */
    private void sendTransferGreeting(CustomerTransfer transfer) {
        try {
            Customer customer = customerRepo.findById(transfer.getCustomerId()).orElse(null);
            QrCode qr = transfer.getQrCodeId() != null
                ? qrCodeRepo.findById(transfer.getQrCodeId()).orElse(null) : null;

            // 客户或活码不存在则跳过
            if (customer == null || qr == null) {
                log.warn("发送交接欢迎语跳过: customer={}, qr={}, transferId={}",
                    customer == null ? "null" : customer.getId(),
                    qr == null ? "null" : qr.getId(),
                    transfer.getId());
                return;
            }

            // 三级回退解析交接问候语配置：① QrCode 新列 → ② welcomeConfig JSON（老活码）→ ③ SystemConfig 全局默认
            TransferGreetingCfg cfg = resolveTransferGreetingConfig(qr);
            if (!cfg.enabled) return;

            // 构建基础模板变量 + 表单字段（含 {{parent_name}} {{school_name}} {{teacher_name}} 等）
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("parent_name", customer.getName());
            vars.put("school_name", qr.getSchoolName() != null ? qr.getSchoolName() : "");
            vars.put("teacher_name", getTeacherName(transfer.getToUserid()));
            // 表单字段（如 grade, class, child_name）覆盖/补充基础变量
            vars.putAll(extractFormFields(transfer.getCustomerId()));

            // 实时检查表单填写状态而非使用 initiate 时的快照：
            // 客户可能在 pending_confirm 期间才填写表单，此处确保 A/B 分支准确
            boolean formFilledNow = checkFormFilled(transfer.getCustomerId());
            if (formFilledNow) {
                // 路径 A：已填写 → 写备注 + 发送交接欢迎语
                String noteTemplate = cfg.filledNote != null ? cfg.filledNote : "";
                String greetingTemplate = cfg.filledGreeting != null ? cfg.filledGreeting : "";

                // 修改客户备注
                if (!noteTemplate.isBlank()) {
                    try {
                        String remark = fillTemplate(noteTemplate, vars);
                        wecomApi.updateRemark(transfer.getToUserid(),
                            customer.getExternalUserid(), remark);
                    } catch (Exception e) {
                        log.warn("修改备注失败: transferId={}", transfer.getId(), e);
                    }
                }

                String greeting = fillTemplate(greetingTemplate, vars);
                wecomApi.sendMessage(transfer.getToUserid(), customer.getExternalUserid(), greeting);
                transfer.setGreetingType(CustomerTransfer.GreetingType.filled);
            } else {
                // 路径 B：未填写 → 提醒填写 + 重新发收集表单
                String greetingTemplate = cfg.unfilledGreeting != null ? cfg.unfilledGreeting : "";
                String resolvedFormUrl = (formUrl != null && !formUrl.isBlank())
                    ? formUrl : "[表单链接]";
                vars.put("form_link", resolvedFormUrl);

                String greeting = fillTemplate(greetingTemplate, vars);
                wecomApi.sendMessage(transfer.getToUserid(), customer.getExternalUserid(), greeting);
                transfer.setGreetingType(CustomerTransfer.GreetingType.unfilled);
            }

            transfer.setGreetingSent(true);
            transfer.setNoteSent(true);
            // 使用 saveAndFlush 强制立即落库，防止 detached entity 在 for 循环中未 flush
            // 导致下一轮 retryFailedGreetings 又被扫出造成重复发送
            transferRepo.saveAndFlush(transfer);
        } catch (WecomPermanentException e) {
            // 永久错误（如 48002 api forbidden）不可恢复，标记已完成避免每 30 分钟无效重试
            transfer.setGreetingSent(true);
            transferRepo.saveAndFlush(transfer);
            log.warn("发送交接欢迎语永久失败(已标记): transferId={}, errcode={}, errmsg={}",
                transfer.getId(), e.getErrcode(), e.getErrmsg());
        } catch (Exception e) {
            log.error("发送交接欢迎语失败(暂态): transferId={}", transfer.getId(), e);
        }
    }

    /**
     * 解析生效的交接问候语配置，三级回退：
     * <ol>
     *   <li>QrCode 新列（{@code transferGreetingEnabled} 非 NULL）→ 使用列值（活码自定义）</li>
     *   <li>QrCode.welcomeConfig JSON（老活码兼容，创建时写入了 transfer_greeting_enabled 字段）</li>
     *   <li>{@link SystemConfig} 全局默认值（管理员在系统配置页可改），硬编码仅作终极兜底</li>
     * </ol>
     */
    private TransferGreetingCfg resolveTransferGreetingConfig(QrCode qr) {
        // ① QrCode 新列：enabled 非 NULL 即为活码自定义，
        //     模板字段若为 NULL 则逐字段回退到 SystemConfig 全局默认（避免空模板发空消息）
        if (qr.getTransferGreetingEnabled() != null) {
            return new TransferGreetingCfg(
                qr.getTransferGreetingEnabled(),
                qr.getTransferFilledNote() != null ? qr.getTransferFilledNote()
                    : getFilledNoteConfig(),
                qr.getTransferFilledGreeting() != null ? qr.getTransferFilledGreeting()
                    : getGlobalConfig("transfer_filled_greeting_default",
                        "{{parent_name}}您好～我是{{school_name}}的专属服务老师{{teacher_name}}，以后孩子的学习资料和购书优惠都由我为您服务 📚"),
                qr.getTransferUnfilledGreeting() != null ? qr.getTransferUnfilledGreeting()
                    : getGlobalConfig("transfer_unfilled_greeting_default",
                        "{{parent_name}}您好～我是{{school_name}}的{{teacher_name}}！为了给您精准推荐适合孩子的学习资料和优惠，请先花30秒填写一下孩子信息哦👇 📚 {{form_link}}")
            );
        }

        // ② welcomeConfig JSON（老活码兼容）
        String welcomeConfigJson = qr.getWelcomeConfig();
        if (welcomeConfigJson != null) {
            try {
                JsonNode wc = objectMapper.readTree(welcomeConfigJson);
                if (wc.has("transfer_greeting_enabled")) {
                    return new TransferGreetingCfg(
                        wc.get("transfer_greeting_enabled").asBoolean(),
                        wc.has("transfer_filled_note") ? wc.get("transfer_filled_note").asText() : "",
                        wc.has("transfer_filled_greeting") ? wc.get("transfer_filled_greeting").asText() : "",
                        wc.has("transfer_unfilled_greeting") ? wc.get("transfer_unfilled_greeting").asText() : ""
                    );
                }
            } catch (Exception e) {
                log.warn("解析 welcomeConfig JSON 失败，降级到系统默认: qrId={}", qr.getId(), e);
            }
        }

        // ③ SystemConfig 全局默认（硬编码兜底）
        return new TransferGreetingCfg(
            getGlobalConfigBool("transfer_greeting_enabled_default", true),
            getFilledNoteConfig(),
            getGlobalConfig("transfer_filled_greeting_default",
                "{{parent_name}}您好～我是{{school_name}}的专属服务老师{{teacher_name}}，以后孩子的学习资料和购书优惠都由我为您服务 📚"),
            getGlobalConfig("transfer_unfilled_greeting_default",
                "{{parent_name}}您好～我是{{school_name}}的{{teacher_name}}！为了给您精准推荐适合孩子的学习资料和优惠，请先花30秒填写一下孩子信息哦👇 📚 {{form_link}}")
        );
    }

    /** 解析后的交接问候语配置（不可变） */
    private record TransferGreetingCfg(boolean enabled, String filledNote,
                                       String filledGreeting, String unfilledGreeting) {}

    /**
     * 解析生效的在职继承成功通知消息，两级回退：
     * <ol>
     *   <li>QrCode.transferSuccessMsg 非 NULL → 使用列值（可为空字符串表示不发送）</li>
     *   <li>{@link SystemConfig} {@code transfer_success_msg_default} 全局默认值</li>
     * </ol>
     *
     * @return 通知消息，{@code null}=不传字段（企微发默认消息），{@code ""}=传空字符串（抑制通知）
     */
    private String resolveTransferSuccessMsg(QrCode qr) {
        if (qr.getTransferSuccessMsg() != null) {
            return qr.getTransferSuccessMsg();
        }
        return systemConfigRepo.findByConfigKey("transfer_success_msg_default")
                .map(SystemConfig::getConfigValue)
                .orElse(null);
    }

    /**
     * 重试场景解析转接成功通知消息：通过 CustomerTransfer 的 qrCodeId 查找活码并回退。
     */
    private String resolveRetryTransferSuccessMsg(CustomerTransfer t) {
        if (t.getQrCodeId() == null) {
            return systemConfigRepo.findByConfigKey("transfer_success_msg_default")
                    .map(SystemConfig::getConfigValue)
                    .orElse(null);
        }
        QrCode qr = qrCodeRepo.findById(t.getQrCodeId()).orElse(null);
        if (qr != null) {
            return resolveTransferSuccessMsg(qr);
        }
        return systemConfigRepo.findByConfigKey("transfer_success_msg_default")
                .map(SystemConfig::getConfigValue)
                .orElse(null);
    }

    /**
     * 对转接成功通知消息做模板变量填充。
     * <p>
     * 支持的变量：{@code {{teacher_name}}} — 新服务老师（接管员工）的真实姓名。
     * 若模板为 {@code null} 或空字符串则原样返回（保留"不发送"/"抑制通知"语义）。
     * </p>
     *
     * @param template      解析后的通知消息模板（可能为 null）
     * @param teacherUserid 接管员工的 userid
     * @return 填充后的消息；template 为 null → null；template 为空串 → 空串
     */
    private String fillTransferSuccessMsg(String template, String teacherUserid) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        return fillTemplate(template, Map.of("teacher_name", getTeacherName(teacherUserid)));
    }

    /**
     * 从 {@link SystemConfig} 表读取 {@code transfer_filled_note_default} 配置。
     * <p>与 {@link #getGlobalConfig} 不同：空字符串视为有效值（表示不设备注），
     * 不会被过滤掉回退到硬编码兜底。</p>
     */
    private String getFilledNoteConfig() {
        return systemConfigRepo.findByConfigKey("transfer_filled_note_default")
            .map(SystemConfig::getConfigValue)
            .filter(v -> v != null)
            .orElse("{{grade}}{{class}} | 孩子：{{child_name}} | 来源：{{school_name}}");
    }

    private String getGlobalConfig(String key, String fallback) {
        return systemConfigRepo.findByConfigKey(key)
            .map(SystemConfig::getConfigValue)
            .filter(v -> v != null && !v.isBlank())
            .orElse(fallback);
    }

    private boolean getGlobalConfigBool(String key, boolean fallback) {
        return systemConfigRepo.findByConfigKey(key)
            .map(SystemConfig::getConfigValue)
            .map(v -> "true".equalsIgnoreCase(v) || "1".equals(v))
            .orElse(fallback);
    }

    /**
     * 填充模板字符串：将模板中的 {@code {{key}}} 占位符替换为实际值。
     * <p>
     * 支持批量替换，不匹配的占位符保留原样。
     * 若 {@code value} 为 {@code null} 则替换为空字符串。
     * </p>
     *
     * @param template 模板字符串，包含 {@code {{key}}} 占位符
     * @param vars     键值对映射表，key 为占位符名称，value 为替换值
     * @return 替换后的字符串；若 template 为 {@code null} 返回空字符串
     */
    private String fillTemplate(String template, Map<String, String> vars) {
        if (template == null) return "";
        String result = template;
        // 遍历所有键值对，逐一替换 {{key}} 占位符
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}",
                entry.getValue() != null ? entry.getValue() : "");
        }
        // 兜底：清理所有未匹配的 {{...}} 占位符，避免原样显示到客户侧
        result = result.replaceAll("\\{\\{[^}]+}}", "");
        return result;
    }

    /** 从 Employee 表获取员工真实姓名，查不到时回退到 userid */
    private String getTeacherName(String userid) {
        return employeeRepo.findByUserid(userid)
            .map(Employee::getName)
            .orElse(userid);
    }

    /** 终端错误码 → 可读原因（用于日志） */
    private static String resolveTerminalReason(int errcode) {
        return switch (errcode) {
            case WecomErrorCodes.NOT_EXTERNAL_CONTACT -> "客户已不是好友";
            case WecomErrorCodes.TICKET_EXPIRED -> "企微票据过期";
            case WecomErrorCodes.TRANSFER_NOT_AVAILABLE -> "客户无法发起继承";
            case WecomErrorCodes.TRANSFER_LIMIT_EXCEEDED -> "接替成员达上限";
            case WecomErrorCodes.TRANSFER_PENDING_EXISTS -> "已有继承中的员工";
            case WecomErrorCodes.DELETED_BY_USER -> "客户已删除服务人员";
            case WecomErrorCodes.TRANSFER_CONFLICT -> "操作冲突(已有进行中转移)";
            default -> "errcode=" + errcode;
        };
    }
}
