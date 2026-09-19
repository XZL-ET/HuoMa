package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerOpenidMap;
import com.bookstore.qrcode.entity.CustomerTag;
import com.bookstore.qrcode.repository.CustomerOpenidMapRepository;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.bookstore.qrcode.wecom.WecomRateLimitException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * external_userid ↔ openid 映射批量构建服务。
 * <p>
 * 对未打标签家长逐个调 {@code convert_to_openid} 落映射表。幂等（已存在跳过）、
 * 限速（每 100 次休眠）、断点续跑（已存在跳过即天然支持重入）。
 * 当前为骨架：同步串行实现，规模达到数万级时应改为异步后台任务（扩展阶段）。
 * </p>
 *
 * @author Bookstore Dev
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenidMappingService {

    private final CustomerRepository customerRepo;
    private final CustomerOpenidMapRepository mapRepo;
    private final WecomApiClient wecomApi;

    /**
     * 批量构建 openid 映射。
     * @return 统计：total / success / skipped / failed
     */
    public Map<String, Object> buildMappings() {
        List<Customer> untagged = customerRepo.findUntaggedParents(
            Customer.CustomerStatus.active, CustomerTag.TagSource.form);
        int success = 0, skipped = 0, failed = 0;
        for (Customer c : untagged) {
            if (mapRepo.findByExternalUserid(c.getExternalUserid()).isPresent()) {
                skipped++;
                continue;
            }
            try {
                String openid = wecomApi.convertToOpenid(c.getExternalUserid());
                mapRepo.save(CustomerOpenidMap.builder()
                    .externalUserid(c.getExternalUserid())
                    .openid(openid)
                    .appid(null)
                    .build());
                success++;
                if (success % 100 == 0) {
                    log.info("openid 映射进度: success={}, skipped={}, failed={}", success, skipped, failed);
                    Thread.sleep(5_000);
                }
            } catch (WecomRateLimitException e) {
                failed++;
                log.warn("openid 转换限流: external={}", c.getExternalUserid());
                try { Thread.sleep(3_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            } catch (Exception e) {
                failed++;
                log.error("openid 转换失败: external={}, err={}", c.getExternalUserid(), e.getMessage());
            }
        }
        log.info("openid 映射构建完成: total={}, success={}, skipped={}, failed={}",
            untagged.size(), success, skipped, failed);
        return Map.of("total", untagged.size(), "success", success,
            "skipped", skipped, "failed", failed);
    }
}
