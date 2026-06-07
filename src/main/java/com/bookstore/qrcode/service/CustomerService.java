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

        if (existing != null) {
            // 更新归属员工（如果换了人）
            existing.setCurrentAgent(userId);
            existing.setUpdatedAt(LocalDateTime.now());
            customerRepo.save(existing);
            return existing.getId();
        }

        // 从企微 API 获取客户详情
        String name = "未知";
        String avatar = null;
        int type = 1;
        try {
            JsonNode detail = wecomApi.getExternalContact(externalUserId);
            if (detail.has("external_contact")) {
                JsonNode ec = detail.get("external_contact");
                name = ec.has("name") ? ec.get("name").asText() : name;
                avatar = ec.has("avatar") ? ec.get("avatar").asText() : null;
                type = ec.has("type") ? ec.get("type").asInt() : 1;
            }
        } catch (Exception e) {
            log.warn("获取客户详情失败, 使用默认值: external={}", externalUserId);
        }

        // 查找来源活码 and schoolId
        Long qrCodeId = null;
        String schoolId = state;
        if (state != null) {
            QrCode qr = qrCodeRepo.findBySchoolId(state).orElse(null);
            if (qr != null) qrCodeId = qr.getId();
        }

        Customer customer = Customer.builder()
            .externalUserid(externalUserId)
            .name(name)
            .avatar(avatar)
            .type(type)
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
}
