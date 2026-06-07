package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 在职继承 — 发起转移、追踪结果、发送交接欢迎语。
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

    /**
     * 发起在职继承：接待员 → 该学校的服务老师。
     */
    @Transactional
    public void initiate(Long customerId, String fromUserid,
                          String externalUserid, String state) {
        if (state == null) return;

        // 找该活码的服务老师
        QrCode qr = qrCodeRepo.findBySchoolId(state).orElse(null);
        if (qr == null) return;

        List<QrAgent> agents = qrAgentRepo.findByQrCodeIdAndStatus(
            qr.getId(), QrAgent.AgentStatus.active);

        // 找服务老师（按排序优先）
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

            // 检查是否已填写收集表单
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
                transfer.setStatus(CustomerTransfer.TransferStatus.pending_confirm);
                log.info("继承发起成功: customer={}, from={}, to={}",
                    externalUserid, fromUserid, serviceAgent.getAgentUserid());
            } else {
                transfer.setStatus(CustomerTransfer.TransferStatus.api_failed);
                transfer.setFailReason("errcode=" + errcode + " " +
                    (result.has("errmsg") ? result.get("errmsg").asText() : ""));
                log.error("继承发起失败: {}", transfer.getFailReason());
            }

            transferRepo.save(transfer);

        } catch (Exception e) {
            log.error("继承发起异常: external={}", externalUserid, e);
            // 记录失败但继续（不影响添加流程）
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
     * 检查客户是否已填写收集表单（查 customer_tag 中有 form 来源的标签）。
     */
    private Boolean checkFormFilled(Long customerId) {
        List<CustomerTag> tags = customerTagRepo.findByCustomerId(customerId);
        return tags.stream().anyMatch(t -> t.getSource() == CustomerTag.TagSource.form);
    }

    /**
     * 追踪继承结果（定时任务调用）。
     */
    @Transactional
    public void trackResults() {
        List<CustomerTransfer> pendings = transferRepo
            .findByStatusAndRetryCountLessThan(CustomerTransfer.TransferStatus.pending_confirm, 10);

        for (CustomerTransfer t : pendings) {
            try {
                JsonNode result = wecomApi.getTransferResult(
                    t.getFromUserid(), t.getToUserid(),
                    customerRepo.findById(t.getCustomerId())
                        .map(Customer::getExternalUserid).orElse(""));

                String transferStatus = result.has("transfer_status")
                    ? result.get("transfer_status").asText() : "";

                switch (transferStatus) {
                    case "TRANSFER_SUCCEED":
                        t.setStatus(CustomerTransfer.TransferStatus.confirmed);
                        t.setConfirmTime(LocalDateTime.now());
                        sendTransferGreeting(t);
                        break;
                    case "TRANSFER_FAIL":
                    case "TRANSFER_REFUSED":
                        t.setStatus(CustomerTransfer.TransferStatus.rejected);
                        t.setConfirmTime(LocalDateTime.now());
                        break;
                    case "TRANSFER_WAIT":
                        t.setRetryCount(t.getRetryCount() + 1);
                        break;
                    default:
                        // 还在等待中
                        t.setRetryCount(t.getRetryCount() + 1);
                        if (t.getRetryCount() > 144) { // 24h * 6次/h = 144次 ≈ 超时
                            t.setStatus(CustomerTransfer.TransferStatus.timeout);
                        }
                }
                transferRepo.save(t);
            } catch (Exception e) {
                t.setRetryCount(t.getRetryCount() + 1);
                transferRepo.save(t);
                log.error("追踪继承结果异常: transferId={}", t.getId(), e);
            }
        }
    }

    /**
     * 发送继承后的交接欢迎语（A/B 分支）。
     */
    private void sendTransferGreeting(CustomerTransfer transfer) {
        try {
            Customer customer = customerRepo.findById(transfer.getCustomerId()).orElse(null);
            QrCode qr = transfer.getQrCodeId() != null
                ? qrCodeRepo.findById(transfer.getQrCodeId()).orElse(null) : null;

            if (customer == null || qr == null) return;

            String welcomeConfigJson = qr.getWelcomeConfig();
            if (welcomeConfigJson == null) return;

            JsonNode wc = objectMapper.readTree(welcomeConfigJson);
            boolean enabled = wc.has("transfer_greeting_enabled")
                && wc.get("transfer_greeting_enabled").asBoolean();

            if (!enabled) return;

            if (Boolean.TRUE.equals(transfer.getFormFilledAtTransfer())) {
                // 路径 A：已填写 → 写备注 + 交接欢迎语
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
                String greeting = fillTemplate(greetingTemplate,
                    Map.of("parent_name", customer.getName(),
                           "school_name", qr.getSchoolName(),
                           "teacher_name", transfer.getToUserid(),
                           "form_link", "[表单链接]")); // TODO: 生成实际表单链接
                wecomApi.sendMessage(transfer.getToUserid(), customer.getExternalUserid(), greeting);
                transfer.setGreetingType(CustomerTransfer.GreetingType.unfilled);
            }

            transfer.setGreetingSent(true);
            transfer.setNoteSent(true);
        } catch (Exception e) {
            log.error("发送交接欢迎语失败: transferId={}", transfer.getId(), e);
        }
    }

    private String fillTemplate(String template, Map<String, String> vars) {
        if (template == null) return "";
        String result = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}",
                entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }
}
