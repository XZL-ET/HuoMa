package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.bookstore.qrcode.wecom.WecomApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在职继承 — 发起转移、追踪结果、发送交接欢迎语。
 * <p>
 * 核心流程：{@link #initiate} 发起 → {@link #trackResults} 轮询企微结果 → {@link #sendTransferGreeting} 发送交接欢迎语。
 * 欢迎语按 {@link CustomerTransfer#formFilledAtTransfer} 走 A/B 分支：已填写则写备注+交接语，未填写则提醒填写。
 * 企微 API 返回 {@code TRANSFER_SUCCEED / FAIL / REFUSED / WAIT} 四种状态构成结果状态机。
 * 超过 24 小时（144 次轮询）仍未确认则标记为 {@link CustomerTransfer.TransferStatus#timeout}。
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
    private final SystemConfigRepository systemConfigRepo;
    private final WecomApiClient wecomApi;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    /** 超时阈值：发起转移后等待 24 小时 */
    private static final Duration TRANSFER_TIMEOUT = Duration.ofHours(24);

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
            .setIfAbsent(lockKey, "1", Duration.ofSeconds(30));
        if (!Boolean.TRUE.equals(locked)) {
            // 锁被占用说明另一线程正在处理同一客户，抛异常让 TransferWorker 重试而非静默跳过
            log.info("客户 {} 继承正在处理中（锁占用），稍后重试", customerId);
            throw new RuntimeException("客户 " + customerId + " 继承锁占用，稍后重试");
        }
        try {
            // ---- 去重：避免同一客户重复发起继承 ----
            if (transferRepo.existsByCustomerIdAndStatusIn(customerId,
                    List.of(CustomerTransfer.TransferStatus.pending_confirm,
                            CustomerTransfer.TransferStatus.confirmed))) {
                log.info("客户 {} 已有进行中/已完成的继承记录，跳过", customerId);
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

            try {
                // 调企微 API 发起继承
                wecomApi.transferCustomer(
                    fromUserid, serviceAgent.getAgentUserid(), externalUserid);
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
                // API 返回业务错误（如参数非法、无权限等）
                transferRepo.save(CustomerTransfer.builder()
                    .customerId(customerId)
                    .fromUserid(fromUserid)
                    .toUserid(serviceAgent.getAgentUserid())
                    .qrCodeId(qr.getId())
                    .transferTime(LocalDateTime.now())
                    .status(CustomerTransfer.TransferStatus.api_failed)
                    .failReason("errcode=" + e.getErrcode() + " " + e.getErrmsg())
                    .build());
                log.error("继承发起失败: errcode={}, errmsg={}", e.getErrcode(), e.getErrmsg());

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
            redisTemplate.delete(lockKey);
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
     * 查询所有状态为 {@link CustomerTransfer.TransferStatus#pending_confirm} 且重试次数 &lt; 10 的记录，
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
    @Transactional
    public void trackResults() {
        // 取出所有待确认且未超过最大重试次数的记录
        List<CustomerTransfer> pendings = transferRepo
            .findByStatusAndRetryCountLessThan(CustomerTransfer.TransferStatus.pending_confirm, 10);

        for (CustomerTransfer t : pendings) {
            try {
                // 调企微 API 查询继承结果，需传原接待员、目标接待员和外部联系人 ID
                // API 返回格式: {"errcode":0, "customer":[{"external_userid":"wmxxx", "status":1}]}
                // status 为整数: 1=接替完毕, 2=原成员拒绝, 3=客户拒绝, 4=待客户确认, 5=客户主动拒绝
                JsonNode result = wecomApi.getTransferResult(
                    t.getFromUserid(), t.getToUserid(),
                    customerRepo.findById(t.getCustomerId())
                        .map(Customer::getExternalUserid).orElse(""));

                // 企微 get_transfer_result 返回 customer[].status 整数:
                //   1=接替完毕  2=等待接替(客户未确认)  3=客户拒绝
                //   4=接替成员客户达上限  5=无接替记录
                // 参考: https://developer.work.weixin.qq.com/document/path/96327
                int apiStatus = result.has("customer")
                    && result.get("customer").isArray()
                    && result.get("customer").size() > 0
                    ? result.get("customer").get(0).get("status").asInt(-1) : -1;

                switch (apiStatus) {
                    case 1: // 接替完毕 → confirmed
                        t.setStatus(CustomerTransfer.TransferStatus.confirmed);
                        t.setConfirmTime(LocalDateTime.now());
                        sendTransferGreeting(t);
                        break;
                    case 2: // 等待接替（客户尚未确认）→ 继续轮询
                        t.setRetryCount(t.getRetryCount() + 1);
                        LocalDateTime waitRefTime = t.getTransferTime() != null
                            ? t.getTransferTime()
                            : (t.getCreatedAt() != null ? t.getCreatedAt() : LocalDateTime.now());
                        if (waitRefTime.plus(TRANSFER_TIMEOUT).isBefore(LocalDateTime.now())) {
                            t.setStatus(CustomerTransfer.TransferStatus.timeout);
                            t.setFailReason("客户超时未确认 (24h)");
                        } else if (t.getRetryCount() >= 10) {
                            t.setStatus(CustomerTransfer.TransferStatus.retry_limit);
                            t.setFailReason("重试次数耗尽 (≥10 次)");
                        }
                        break;
                    case 3: // 客户拒绝 → rejected
                    case 4: // 接替成员客户数达上限（终态，重试无用）
                        t.setStatus(CustomerTransfer.TransferStatus.rejected);
                        t.setConfirmTime(LocalDateTime.now());
                        if (apiStatus == 4) {
                            t.setFailReason("接替成员客户数已达上限");
                        }
                        break;
                    default:
                        // 状态码 5（无接替记录）/ -1（customer 数组为空）/ 未知码
                        log.warn("getTransferResult 返回非预期状态: apiStatus={}, transferId={}",
                            apiStatus, t.getId());
                        t.setRetryCount(t.getRetryCount() + 1);
                        LocalDateTime referenceTime = t.getTransferTime() != null
                            ? t.getTransferTime()
                            : (t.getCreatedAt() != null ? t.getCreatedAt() : LocalDateTime.now());
                        if (referenceTime.plus(TRANSFER_TIMEOUT).isBefore(LocalDateTime.now())) {
                            t.setStatus(CustomerTransfer.TransferStatus.timeout);
                            t.setFailReason("转移超时 (24h)");
                        } else if (t.getRetryCount() >= 10) {
                            t.setStatus(CustomerTransfer.TransferStatus.retry_limit);
                            t.setFailReason("重试次数耗尽 (≥10 次)");
                        }
                }
                transferRepo.save(t);
            } catch (WecomApiException e) {
                // API 调用失败，累加重试次数
                t.setRetryCount(t.getRetryCount() + 1);
                if (t.getRetryCount() >= 10) {
                    t.setStatus(CustomerTransfer.TransferStatus.retry_limit);
                    t.setFailReason("API 追踪失败耗尽: " + e.getErrmsg());
                }
                transferRepo.save(t);
                log.error("追踪继承结果 API 失败: transferId={}, errcode={}, errmsg={}",
                    t.getId(), e.getErrcode(), e.getErrmsg());
            } catch (Exception e) {
                // 单条追踪异常不中断批量处理，计数后继续下一条
                t.setRetryCount(t.getRetryCount() + 1);
                if (t.getRetryCount() >= 10) {
                    t.setStatus(CustomerTransfer.TransferStatus.retry_limit);
                    t.setFailReason("追踪异常耗尽: " + e.getMessage());
                }
                transferRepo.save(t);
                log.error("追踪继承结果异常: transferId={}", t.getId(), e);
            }
        }

        // 安全网：处理主循环前就已耗尽（retryCount ≥10）的 pending_confirm 记录（历史遗留数据）
        // 新产生的 retry_limit 已在主循环内联标记，此处仅兜底
        List<CustomerTransfer> exhausted = transferRepo
            .findByStatusAndRetryCountGreaterThanEqual(
                CustomerTransfer.TransferStatus.pending_confirm, 10);
        for (CustomerTransfer t : exhausted) {
            t.setStatus(CustomerTransfer.TransferStatus.retry_limit);
            t.setFailReason("重试次数耗尽 (≥10 次)");
            transferRepo.save(t);
            log.warn("转移记录标记为 retry_limit: id={}, customerId={}", t.getId(), t.getCustomerId());
        }
        if (!exhausted.isEmpty()) {
            log.info("标记 {} 条重试耗尽记录为 retry_limit", exhausted.size());
        }
    }

    /**
     * 重试 API 调用失败的转移记录（由定时任务周期性调用）。
     * <p>
     * 查询所有状态为 {@link CustomerTransfer.TransferStatus#api_failed} 且重试次数 < 3 的记录，
     * 重新调用企微 {@code transfer_customer} API。成功后状态变为 pending_confirm，
     * 失败则累加重试次数，达到上限（≥3）标记为 retry_limit。
     * </p>
     */
    @Transactional
    public void retryFailedTransfers() {
        List<CustomerTransfer> failed = transferRepo
            .findByStatusAndRetryCountLessThan(CustomerTransfer.TransferStatus.api_failed, 3);

        for (CustomerTransfer t : failed) {
            // ---- Redis 分布式锁：防止同客户两条 api_failed 记录并发重试 ----
            String retryLockKey = "lock:transfer:" + t.getCustomerId();
            Boolean retryLocked = redisTemplate.opsForValue()
                .setIfAbsent(retryLockKey, "1", Duration.ofSeconds(30));
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
                Customer customer = customerRepo.findById(t.getCustomerId()).orElse(null);
                if (customer == null) {
                    t.setStatus(CustomerTransfer.TransferStatus.retry_limit);
                    t.setFailReason("客户不存在");
                    transferRepo.save(t);
                    continue;
                }
                wecomApi.transferCustomer(t.getFromUserid(), t.getToUserid(),
                    customer.getExternalUserid());
                t.setStatus(CustomerTransfer.TransferStatus.pending_confirm);
                t.setRetryCount(t.getRetryCount() + 1);
                transferRepo.save(t);
                log.info("api_failed 重试成功: transferId={}", t.getId());
            } catch (WecomApiException e) {
                t.setRetryCount(t.getRetryCount() + 1);
                t.setFailReason("重试失败: errcode=" + e.getErrcode() + " " + e.getErrmsg());
                if (t.getRetryCount() >= 3) {
                    t.setStatus(CustomerTransfer.TransferStatus.retry_limit);
                }
                transferRepo.save(t);
                log.error("api_failed 重试失败: transferId={}, errcode={}", t.getId(), e.getErrcode());
            } catch (Exception e) {
                t.setRetryCount(t.getRetryCount() + 1);
                if (t.getRetryCount() >= 3) {
                    t.setStatus(CustomerTransfer.TransferStatus.retry_limit);
                    t.setFailReason("重试耗尽: " + e.getMessage());
                }
                transferRepo.save(t);
                log.error("api_failed 重试异常: transferId={}", t.getId(), e);
            } finally {
                redisTemplate.delete(retryLockKey);
            }
        }
        if (!failed.isEmpty()) {
            log.info("api_failed 重试完成: 处理 {} 条", failed.size());
        }
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
            if (customer == null || qr == null) return;

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

            if (Boolean.TRUE.equals(transfer.getFormFilledAtTransfer())) {
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
        } catch (Exception e) {
            log.error("发送交接欢迎语失败: transferId={}", transfer.getId(), e);
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
                    : getGlobalConfig("transfer_filled_note_default",
                        "{{grade}}{{class}} | 孩子：{{child_name}} | 来源：{{school_name}}"),
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
            getGlobalConfig("transfer_filled_note_default",
                "{{grade}}{{class}} | 孩子：{{child_name}} | 来源：{{school_name}}"),
            getGlobalConfig("transfer_filled_greeting_default",
                "{{parent_name}}您好～我是{{school_name}}的专属服务老师{{teacher_name}}，以后孩子的学习资料和购书优惠都由我为您服务 📚"),
            getGlobalConfig("transfer_unfilled_greeting_default",
                "{{parent_name}}您好～我是{{school_name}}的{{teacher_name}}！为了给您精准推荐适合孩子的学习资料和优惠，请先花30秒填写一下孩子信息哦👇 📚 {{form_link}}")
        );
    }

    /** 解析后的交接问候语配置（不可变） */
    private record TransferGreetingCfg(boolean enabled, String filledNote,
                                       String filledGreeting, String unfilledGreeting) {}

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
        return result;
    }

    /** 从 Employee 表获取员工真实姓名，查不到时回退到 userid */
    private String getTeacherName(String userid) {
        return employeeRepo.findByUserid(userid)
            .map(Employee::getName)
            .orElse(userid);
    }
}
