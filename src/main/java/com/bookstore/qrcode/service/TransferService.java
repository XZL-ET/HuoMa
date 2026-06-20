package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
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
    private final WecomApiClient wecomApi;
    private final ObjectMapper objectMapper;

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
     *   <li>根据 {@code state} 查找活码及其中激活状态的服务老师（按排序优先）</li>
     *   <li>调用企微 API {@code transfer_customer} 发起转移</li>
     *   <li>通过 {@link #checkFormFilled} 检查客户是否已填写收集表单</li>
     *   <li>记录 {@link CustomerTransfer} 持久化到数据库，API 成功则为 pending_confirm，失败则为 api_failed</li>
     * </ol>
     * </p>
     *
     * @param customerId     客户 ID
     * @param fromUserid     原接待成员（企微 userid）
     * @param externalUserid 外部联系人 ID（企微 external_userid）
     * @param state          活码标识，用于查找对应的活码（通常为 schoolId）
     * @throws org.springframework.dao.DataAccessException 数据库写入异常
     */
    @Transactional
    public void initiate(Long customerId, String fromUserid,
                          String externalUserid, String state) {
        if (state == null) return;

        // 根据 state（schoolId）查找活码，无对应活码则跳过
        QrCode qr = qrCodeRepo.findBySchoolId(state).orElse(null);
        if (qr == null) return;

        List<QrAgent> agents = qrAgentRepo.findByQrCodeIdAndStatus(
            qr.getId(), QrAgent.AgentStatus.active);

        // 在活跃的接待员中筛选服务老师（按排序取第一个）
        QrAgent serviceAgent = agents.stream()
            .filter(a -> a.getRole() == QrAgent.AgentRole.service
                       && a.getStatus() == QrAgent.AgentStatus.active)
            .findFirst().orElse(null);

        if (serviceAgent == null) {
            log.info("活码 {} 未配置服务老师，跳过继承", qr.getId());
            return;
        }

        try {
            // 调企微 API 发起继承
            JsonNode result = wecomApi.transferCustomer(
                fromUserid, serviceAgent.getAgentUserid(), externalUserid);

            int errcode = result.has("errcode") ? result.get("errcode").asInt() : -1;

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
                .build();

            if (errcode == 0) {
                // errcode=0 表示 API 调用成功，等待客户确认
                transfer.setStatus(CustomerTransfer.TransferStatus.pending_confirm);
                log.info("继承发起成功: customer={}, from={}, to={}",
                    externalUserid, fromUserid, serviceAgent.getAgentUserid());
            } else {
                // errcode!=0 表示 API 返回业务错误（如参数非法、无权限等）
                transfer.setStatus(CustomerTransfer.TransferStatus.api_failed);
                transfer.setFailReason("errcode=" + errcode + " " +
                    (result.has("errmsg") ? result.get("errmsg").asText() : ""));
                log.error("继承发起失败: {}", transfer.getFailReason());
            }

            transferRepo.save(transfer);

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
     * 查询所有状态为 {@link CustomerTransfer.TransferStatus#pending_confirm} 且重试次数 ≤ 10 的记录，
     * 逐一调用企微 API {@code get_transfer_result} 获取最新状态，并按结果状态机处理：
     * <ul>
     *   <li>{@code TRANSFER_SUCCEED} → {@link CustomerTransfer.TransferStatus#confirmed}，
     *       并触发 {@link #sendTransferGreeting} 发送交接欢迎语</li>
     *   <li>{@code TRANSFER_FAIL} / {@code TRANSFER_REFUSED} → {@link CustomerTransfer.TransferStatus#rejected}</li>
     *   <li>{@code TRANSFER_WAIT} → 累加重试次数</li>
     *   <li>其他（含默认）→ 累加重试次数，超过 144 次（≈24h）标记为 {@link CustomerTransfer.TransferStatus#timeout}</li>
     * </ul>
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
                JsonNode result = wecomApi.getTransferResult(
                    t.getFromUserid(), t.getToUserid(),
                    customerRepo.findById(t.getCustomerId())
                        .map(Customer::getExternalUserid).orElse(""));

                String transferStatus = result.has("transfer_status")
                    ? result.get("transfer_status").asText() : "";

                switch (transferStatus) {
                    case "TRANSFER_SUCCEED":
                        // 继承成功 → 确认并发送交接欢迎语
                        t.setStatus(CustomerTransfer.TransferStatus.confirmed);
                        t.setConfirmTime(LocalDateTime.now());
                        sendTransferGreeting(t);
                        break;
                    case "TRANSFER_FAIL":
                    case "TRANSFER_REFUSED":
                        // 继承失败或被客户拒绝 → 标记为拒绝
                        t.setStatus(CustomerTransfer.TransferStatus.rejected);
                        t.setConfirmTime(LocalDateTime.now());
                        break;
                    case "TRANSFER_WAIT":
                        // 客户尚未确认 → 下次再查
                        t.setRetryCount(t.getRetryCount() + 1);
                        break;
                    default:
                        // 未知状态或 API 未返回有效状态 → 继续等待
                        t.setRetryCount(t.getRetryCount() + 1);
                        // 优先用 transferTime，若为 null 则降级为 createdAt，再为 null 则用当前时间
                        LocalDateTime referenceTime = t.getTransferTime() != null
                            ? t.getTransferTime()
                            : (t.getCreatedAt() != null ? t.getCreatedAt() : LocalDateTime.now());
                        if (referenceTime.plus(TRANSFER_TIMEOUT).isBefore(LocalDateTime.now())) {
                            t.setStatus(CustomerTransfer.TransferStatus.timeout);
                            t.setFailReason("转移超时 (24h)");
                        }
                }
                transferRepo.save(t);
            } catch (Exception e) {
                // 单条追踪异常不中断批量处理，计数后继续下一条
                t.setRetryCount(t.getRetryCount() + 1);
                transferRepo.save(t);
                log.error("追踪继承结果异常: transferId={}", t.getId(), e);
            }
        }
    }

    /**
     * 发送继承后的交接欢迎语（A/B 分支）。
     * <p>
     * 根据 {@link CustomerTransfer#formFilledAtTransfer} 的值走不同分支：
     * <ul>
     *   <li><b>路径 A（已填写）</b>：发送带备注和交接语的消息，模板变量含家长名、学校名、老师名</li>
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

            String welcomeConfigJson = qr.getWelcomeConfig();
            // 未配置欢迎语则跳过
            if (welcomeConfigJson == null) return;

            JsonNode wc = objectMapper.readTree(welcomeConfigJson);
            // 交接欢迎语开关未开启则跳过
            boolean enabled = wc.has("transfer_greeting_enabled")
                && wc.get("transfer_greeting_enabled").asBoolean();

            if (!enabled) return;

            if (Boolean.TRUE.equals(transfer.getFormFilledAtTransfer())) {
                // 路径 A：已填写 → 写备注 + 发送交接欢迎语
                String noteTemplate = wc.has("transfer_filled_note")
                    ? wc.get("transfer_filled_note").asText() : "";
                String greetingTemplate = wc.has("transfer_filled_greeting")
                    ? wc.get("transfer_filled_greeting").asText() : "";
                // TODO: 从客户标签中提取年级/班级/孩子名填充模板变量

                String greeting = fillTemplate(greetingTemplate,
                    Map.of("parent_name", customer.getName(),
                           "school_name", qr.getSchoolName(),
                           "teacher_name", transfer.getToUserid()));
                wecomApi.sendMessage(transfer.getToUserid(), customer.getExternalUserid(), greeting);
                transfer.setGreetingType(CustomerTransfer.GreetingType.filled);
            } else {
                // 路径 B：未填写 → 提醒填写 + 重新发收集表单
                String greetingTemplate = wc.has("transfer_unfilled_greeting")
                    ? wc.get("transfer_unfilled_greeting").asText() : "";
                String resolvedFormUrl = (formUrl != null && !formUrl.isBlank())
                    ? formUrl : "[表单链接]";
                String greeting = fillTemplate(greetingTemplate,
                    Map.of("parent_name", customer.getName(),
                           "school_name", qr.getSchoolName(),
                           "teacher_name", transfer.getToUserid(),
                           "form_link", resolvedFormUrl));
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
}
