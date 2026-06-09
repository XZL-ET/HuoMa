package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepo;
    private final QrCodeRepository qrCodeRepo;
    private final CustomerTagRepository customerTagRepo;
    private final TagRepository tagRepo;
    private final WecomApiClient wecomApi;

    /**
     * 从回调创建或更新客户。
     * @return customerId
     */
    @Transactional
    public Long upsertFromCallback(String externalUserId, String userId, String state) {
        Customer existing = customerRepo.findByExternalUserid(externalUserId).orElse(null);

        // 查找来源活码 and schoolId
        Long qrCodeId = null;
        String schoolId = null;
        if (state != null && !state.isBlank()) {
            QrCode qr = qrCodeRepo.findBySchoolId(state).orElse(null);
            if (qr != null) {
                qrCodeId = qr.getId();
                schoolId = state;
            } else {
                log.warn("回调 state 未匹配到活码 school_id: state={}, external={}", state, externalUserId);
            }
        }

        if (existing != null) {
            // 更新归属员工和来源活码（用户可能扫了新的活码）
            existing.setCurrentAgent(userId);
            existing.setUpdatedAt(LocalDateTime.now());
            // 如果新回调有匹配的活码，更新来源信息
            if (qrCodeId != null) {
                existing.setSourceQrId(qrCodeId);
                existing.setSchoolId(schoolId);
            }
            // 如果之前是 deleted 状态，恢复为 active
            if (existing.getStatus() == Customer.CustomerStatus.deleted) {
                existing.setStatus(Customer.CustomerStatus.active);
            }
            customerRepo.save(existing);
            return existing.getId();
        }

        // 新客户：从企微 API 获取客户详情
        String name = "未知";
        String avatar = null;
        String unionid = null;
        int type = 1;
        try {
            JsonNode detail = wecomApi.getExternalContact(externalUserId);
            if (detail.has("external_contact")) {
                JsonNode ec = detail.get("external_contact");
                name = ec.has("name") ? ec.get("name").asText() : name;
                avatar = ec.has("avatar") ? ec.get("avatar").asText() : null;
                type = ec.has("type") ? ec.get("type").asInt() : 1;
                unionid = ec.has("unionid") && !ec.get("unionid").isNull()
                    ? ec.get("unionid").asText() : null;
            }
        } catch (Exception e) {
            log.warn("获取客户详情失败, 使用默认值: external={}", externalUserId);
        }

        Customer customer = Customer.builder()
            .externalUserid(externalUserId)
            .name(name)
            .avatar(avatar)
            .type(type)
            .unionid(unionid)
            .addedAgent(userId)
            .currentAgent(userId)
            .sourceQrId(qrCodeId)
            .schoolId(schoolId)
            .status(Customer.CustomerStatus.active)
            .addTime(LocalDateTime.now())
            .build();
        customer = customerRepo.save(customer);
        return customer.getId();
    }

    /**
     * 处理客户删除事件。
     */
    @Transactional
    public void handleDelete(JsonNode event) {
        String externalUserId = event.has("external_userid")
            ? event.get("external_userid").asText() : null;
        if (externalUserId == null) return;

        Customer customer = customerRepo.findByExternalUserid(externalUserId).orElse(null);
        if (customer != null) {
            customer.setStatus(Customer.CustomerStatus.deleted);
            customerRepo.save(customer);
        }
    }

    /**
     * 客户搜索（管理后台）。
     */
    public Page<Customer> search(String keyword, String schoolId, String currentAgent,
                                  Customer.CustomerStatus status,
                                  LocalDateTime startTime, LocalDateTime endTime,
                                  Pageable pageable) {
        return customerRepo.search(keyword, schoolId, currentAgent,
            status, startTime, endTime, pageable);
    }

    /**
     * 客户详情。
     */
    public Customer getById(Long id) {
        return customerRepo.findById(id)
            .orElseThrow(() -> new RuntimeException("客户不存在: " + id));
    }

    /**
     * 客户的标签列表。
     */
    public List<CustomerTag> getTags(Long customerId) {
        return customerTagRepo.findByCustomerId(customerId);
    }

    /**
     * 今日新增客户数。
     */
    public long countToday() {
        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        LocalDateTime todayEnd = LocalDateTime.now();
        return customerRepo.countByAddTimeBetween(todayStart, todayEnd);
    }

    /**
     * 累计客户数。
     */
    public long countTotal() {
        return customerRepo.count();
    }

    /**
     * 修复存量客户数据：从企微 API 回填 unionid 等缺失字段。
     * @return 修复数量
     */
    @Transactional
    public int repairCustomerData() {
        List<Customer> all = customerRepo.findAll();
        int repaired = 0;
        for (Customer c : all) {
            boolean changed = false;
            try {
                JsonNode detail = wecomApi.getExternalContact(c.getExternalUserid());
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
        log.info("客户数据修复完成: 共{}条, 修复{}条", all.size(), repaired);
        return repaired;
    }

    /**
     * 手动创建测试客户（开发调试用）。
     * 当本地环境无法接收企微回调时，通过此方法添加模拟客户数据。
     */
    @Transactional
    public Customer createManual(String name, String externalUserid,
                                  String agentUserid, String schoolId, Long qrCodeId) {
        if (customerRepo.existsByExternalUserid(externalUserid)) {
            throw new RuntimeException("客户已存在: " + externalUserid);
        }

        Customer customer = Customer.builder()
            .externalUserid(externalUserid)
            .name(name)
            .type(1)
            .addedAgent(agentUserid)
            .currentAgent(agentUserid)
            .sourceQrId(qrCodeId)
            .schoolId(schoolId)
            .status(Customer.CustomerStatus.active)
            .addTime(LocalDateTime.now())
            .build();
        return customerRepo.save(customer);
    }
}
