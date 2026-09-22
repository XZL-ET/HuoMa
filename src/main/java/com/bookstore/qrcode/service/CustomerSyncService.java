package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerRelation;
import com.bookstore.qrcode.entity.CustomerRelation.RelationStatus;
import com.bookstore.qrcode.repository.CustomerRelationRepository;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerSyncService {

    private final WecomApiClient wecomApi;
    private final CustomerRepository customerRepo;
    private final CustomerRelationRepository relationRepo;
    private final CustomerRelationService relationService;

    @org.springframework.beans.factory.annotation.Value("${app.customer-relation-sync.enabled:false}")
    private boolean syncEnabled;

    @org.springframework.scheduling.annotation.Scheduled(cron = "${app.customer-relation-sync.cron:0 37 * * * *}")
    public void scheduledSync() {
        if (!syncEnabled) return;
        try { syncOnce(); }
        catch (Exception e) { log.error("customer_relation 全量同步失败", e); }
    }

    /**
     * 全量同步一次：逐在职员工拉客户列表，upsert 关系 + removed 对账。
     * 遵循 spec §4.1/§4.4 铁律：单员工失败整员工跳过；来源字段 COALESCE；removed 只针对 T0 前稳定行。
     *
     * @return 处理的员工数
     */
    public int syncOnce() {
        JsonNode resp = wecomApi.getUserList();
        if (resp == null || !resp.has("userlist") || !resp.get("userlist").isArray()) {
            log.warn("企微员工列表为空或格式异常，跳过同步");
            return 0;
        }
        LocalDateTime t0 = LocalDateTime.now();
        int processed = 0;
        for (JsonNode u : resp.get("userlist")) {
            String userid = u.has("userid") ? u.get("userid").asText() : "";
            int status = u.has("status") ? u.get("status").asInt(-1) : -1;
            if (userid.isEmpty()) continue;
            if (status != 1) continue; // 只同步已激活员工（1=已激活），离职/禁用不在对账范围
            processed++;
            syncEmployee(userid, t0);
        }
        log.info("customer_relation 全量同步完成: 处理 {} 员工", processed);
        return processed;
    }

    private void syncEmployee(String userid, LocalDateTime t0) {
        List<String> snapshotExternalUserids;
        try {
            JsonNode list = wecomApi.getExternalContactList(userid);
            // 铁律：成功但响应格式异常（缺失/非数组 external_userid）同样整员工跳过，绝不当空列表处理
            if (list == null || !list.has("external_userid") || !list.get("external_userid").isArray()) {
                log.warn("客户列表响应格式异常（缺少 external_userid 数组），整员工跳过（留待下轮）: userid={}", userid);
                return;
            }
            snapshotExternalUserids = new ArrayList<>();
            for (JsonNode e : list.get("external_userid")) snapshotExternalUserids.add(e.asText());
        } catch (Exception e) {
            // 铁律：单员工 API 失败整体跳过，绝不当空列表处理
            log.warn("客户列表拉取失败，整员工跳过（留待下轮）: userid={}, err={}", userid, e.getMessage());
            return;
        }

        // external_userid → Customer 批量反查（来源字段近似回填需完整实体）
        Map<String, Customer> customerByExternal = new HashMap<>();
        if (!snapshotExternalUserids.isEmpty()) {
            for (Customer c : customerRepo.findByExternalUseridIn(snapshotExternalUserids)) {
                customerByExternal.put(c.getExternalUserid(), c);
            }
        }

        // upsert 快照中的关系（来源字段 COALESCE；稀疏 customer 无来源，传 null）
        for (String externalUserid : snapshotExternalUserids) {
            Customer c = customerByExternal.get(externalUserid);
            if (c == null) {
                Long backfilledId = backfillSparseCustomer(externalUserid);
                if (backfilledId == null) continue;
                relationService.upsertActive(backfilledId, userid, null, null, null);
                continue;
            }
            relationService.upsertActive(c.getId(), userid, c.getSourceQrId(), c.getSchoolId(), c.getAddTime());
        }

        // removed 对账：该员工名下 active 且 updated_at < T0、又不在快照里 → 置 removed
        List<CustomerRelation> active = relationRepo.findByEmployeeUseridAndStatus(userid, RelationStatus.active);
        for (CustomerRelation rel : active) {
            if (rel.getUpdatedAt() != null && !rel.getUpdatedAt().isBefore(t0)) continue; // 同步期间新增，留待下轮
            Long relCustomerId = rel.getCustomerId();
            boolean inSnapshot = customerRepo.findById(relCustomerId)
                .map(c -> snapshotExternalUserids.contains(c.getExternalUserid()))
                .orElse(false);
            if (!inSnapshot) {
                relationService.markRemoved(relCustomerId, userid);
            }
        }
    }

    /** 回调丢失的 external_userid 补建稀疏 customer 记录（只写 external_userid，其余 DataFillWorker 异步补）。 */
    private Long backfillSparseCustomer(String externalUserid) {
        Customer c = Customer.builder()
            .externalUserid(externalUserid)
            .name("未知")
            .type(1)
            .status(Customer.CustomerStatus.active)
            .build();
        try {
            c = customerRepo.save(c);
            return c.getId();
        } catch (Exception e) {
            log.warn("稀疏 customer 补建失败: external={}, err={}", externalUserid, e.getMessage());
            return null;
        }
    }
}
