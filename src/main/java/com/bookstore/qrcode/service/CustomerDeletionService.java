package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerDeletionEvent;
import com.bookstore.qrcode.repository.CustomerDeletionEventRepository;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 客户删除关系事件记录服务。
 * <p>
 * 消费企微回调，将「客户删除员工」({@code del_follow_user}) 与「员工删除客户」
 * ({@code del_external_contact}) 落库到 {@link CustomerDeletionEvent}，
 * 供每日日报汇总推送。
 * </p>
 *
 * @author Bookstore Dev
 * @since 2.x
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerDeletionService {

    private final CustomerDeletionEventRepository deletionRepo;
    private final CustomerRepository customerRepo;
    private final WecomApiClient wecomApi;

    /**
     * 记录「客户删除员工」事件（企微 del_follow_user）。
     *
     * @param event 回调事件 JSON，需含 external_userid 与 userid 字段
     */
    @Transactional
    public void recordCustomerDeletedAgent(JsonNode event) {
        String externalUserId = getStr(event, "external_userid");
        String userId = getStr(event, "userid");
        if (externalUserId == null || userId == null) {
            log.warn("客户删除员工事件缺少关键字段: external={}, userid={}", externalUserId, userId);
            return;
        }
        backfillCustomerName(externalUserId);
        save(externalUserId, userId, CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, null);
    }

    /**
     * 记录「员工删除客户」事件（企微 del_external_contact）。
     *
     * <p>source 字段已弃用（企微回调不携带，恒为 null），保留读取仅为兼容历史结构。</p>
     *
     * @param event 回调事件 JSON，需含 external_userid 与 userid 字段，source 可选
     */
    @Transactional
    public void recordAgentDeletedCustomer(JsonNode event) {
        String externalUserId = getStr(event, "external_userid");
        String userId = getStr(event, "userid");
        if (externalUserId == null || userId == null) {
            log.warn("员工删除客户事件缺少关键字段: external={}, userid={}", externalUserId, userId);
            return;
        }
        save(externalUserId, userId, CustomerDeletionEvent.Direction.AGENT_DELETED_CUSTOMER,
            getStr(event, "source"));
    }

    /**
     * 删除发生时兜底补全客户名：本地 Customer.name 仍为「未知」时，调企微
     * {@code externalcontact/get} 补一次真实昵称，失败静默（客户可能已彻底脱离企业）。
     */
    private void backfillCustomerName(String externalUserId) {
        Customer customer = customerRepo.findByExternalUserid(externalUserId).orElse(null);
        if (customer == null || !"未知".equals(customer.getName())) {
            return;
        }
        try {
            JsonNode detail = wecomApi.getExternalContact(externalUserId);
            if (detail.has("external_contact") && detail.get("external_contact").has("name")) {
                customer.setName(detail.get("external_contact").get("name").asText());
                customerRepo.save(customer);
                log.info("删除事件兜底补全客户名: external={}, name={}",
                    externalUserId, customer.getName());
            }
        } catch (Exception e) {
            log.warn("删除事件补名失败（不影响事件记录）: external={}", externalUserId, e);
        }
    }

    private void save(String externalUserId, String userId,
                      CustomerDeletionEvent.Direction direction, String source) {
        deletionRepo.save(CustomerDeletionEvent.builder()
            .externalUserid(externalUserId)
            .userid(userId)
            .direction(direction)
            .source(source)
            .deletedAt(LocalDateTime.now())
            .build());
    }

    private String getStr(JsonNode event, String field) {
        return event.has(field) && !event.get(field).isNull()
            ? event.get(field).asText() : null;
    }
}
