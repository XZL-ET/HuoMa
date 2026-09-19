package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerTag;
import com.bookstore.qrcode.entity.FormResendTask;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.FormResendTaskRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 补发表单群发服务。
 * <p>
 * 按员工分批对「未打标签家长」发起企微群发（{@code add_msg_template}），
 * 每批 ≤ 1 万 external_userid；发起后给相关员工发内部应用消息催办。
 * 群发为半自动：员工在企微端确认后才真正发出。
 * </p>
 *
 * @author Bookstore Dev
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormResendService {

    private static final int MAX_BATCH_SIZE = 10_000;
    private static final String GROUP_TEXT = "填写年级和科目，免费领本年级试题";
    private static final String NOTIFY_TEXT = "你有一批群发待确认，请到「客户联系 → 群发助手」点击发送";

    private final CustomerRepository customerRepo;
    private final FormResendTaskRepository taskRepo;
    private final WecomApiClient wecomApi;
    private final ObjectMapper objectMapper;

    @Value("${app.base-url:https://huoma.gsxhsd.com}")
    private String baseUrl;

    /** 未打标签家长总数（用于后台预览） */
    public long countUntagged() {
        return customerRepo.countUntaggedByAgent(Customer.CustomerStatus.active, CustomerTag.TagSource.form)
            .stream()
            .mapToLong(row -> ((Number) row[1]).longValue()).sum();
    }

    /** 按员工分组的未打标签数量（用于后台预览） */
    public List<Map<String, Object>> untaggedByAgent() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : customerRepo.countUntaggedByAgent(Customer.CustomerStatus.active, CustomerTag.TagSource.form)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("agent", row[0]);
            m.put("count", row[1]);
            result.add(m);
        }
        return result;
    }

    /**
     * 发起群发：按员工分批，为每批调用 add_msg_template 并落任务，随后催办员工。
     * @param agentUserid 可选，指定则仅对该员工发起；null 则全部
     * @return 统计结果
     */
    public Map<String, Object> initiateResend(String agentUserid) {
        List<Object[]> agentCounts = customerRepo.countUntaggedByAgent(Customer.CustomerStatus.active, CustomerTag.TagSource.form);
        int total = 0, tasks = 0, failed = 0;
        List<String> notifiedAgents = new ArrayList<>();

        for (Object[] row : agentCounts) {
            String agent = (String) row[0];
            if (agentUserid != null && !agentUserid.isBlank() && !agentUserid.equals(agent)) {
                continue;
            }
            List<Customer> customers = customerRepo.findUntaggedByAgent(agent, Customer.CustomerStatus.active, CustomerTag.TagSource.form);
            if (customers.isEmpty()) continue;

            for (int i = 0; i < customers.size(); i += MAX_BATCH_SIZE) {
                List<Customer> batch = customers.subList(i, Math.min(i + MAX_BATCH_SIZE, customers.size()));
                List<String> externalUserids = batch.stream()
                    .map(Customer::getExternalUserid).collect(Collectors.toList());
                try {
                    JsonNode resp = wecomApi.addMsgTemplate("single", externalUserids,
                        agent, GROUP_TEXT, buildLinkAttachment());
                    String msgid = resp.has("msgid") ? resp.get("msgid").asText() : null;
                    String failList = resp.has("fail_list") ? resp.get("fail_list").toString() : null;
                    taskRepo.save(FormResendTask.builder()
                        .sender(agent)
                        .externalUserids(objectMapper.writeValueAsString(externalUserids))
                        .msgid(msgid)
                        .failList(failList)
                        .coveredCount(batch.size())
                        .status("created")
                        .build());
                    total += batch.size();
                    tasks++;
                } catch (Exception e) {
                    failed++;
                    log.error("群发创建失败: sender={}, err={}", agent, e.getMessage());
                    try {
                        taskRepo.save(FormResendTask.builder()
                            .sender(agent)
                            .externalUserids(objectMapper.writeValueAsString(externalUserids))
                            .coveredCount(batch.size())
                            .status("failed")
                            .build());
                    } catch (Exception ignored) {}
                }
                try { Thread.sleep(200); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            try {
                wecomApi.sendAppMessage(agent, NOTIFY_TEXT);
                notifiedAgents.add(agent);
            } catch (Exception e) {
                log.warn("催办消息发送失败: agent={}, err={}", agent, e.getMessage());
            }
        }

        log.info("补发群发完成: total={}, tasks={}, failed={}, agents={}",
            total, tasks, failed, notifiedAgents.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("tasks", tasks);
        result.put("failed", failed);
        result.put("agents", notifiedAgents.size());
        return result;
    }

    public List<FormResendTask> listTasks() {
        return taskRepo.findAllByOrderByCreatedAtDesc();
    }

    /** 组装群发的 link 卡片附件：统一表单链接（不带 customerId，靠 openid 反查识别） */
    private List<Map<String, Object>> buildLinkAttachment() {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("title", GROUP_TEXT);
        link.put("url", baseUrl + "/form/resend");
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("msgtype", "link");
        attachment.put("link", link);
        List<Map<String, Object>> attachments = new ArrayList<>();
        attachments.add(attachment);
        return attachments;
    }
}
