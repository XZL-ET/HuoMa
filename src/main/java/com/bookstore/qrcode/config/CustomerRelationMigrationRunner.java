package com.bookstore.qrcode.config;

import com.bookstore.qrcode.service.CustomerSyncService;
import com.bookstore.qrcode.service.CustomerTransferQrBackfillService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 一次性迁移触发器（临时，验证完成后可删）。
 * <p>
 * 部署 customer_relation 对齐时，设 {@code app.customer-relation-migrate.enabled=true}
 * 启动一次，依次执行：1) 回填 customer_transfer.qr_code_id（补齐去重维度）；
 * 2) 全量同步 customer_relation（灌存量 follow_user）。默认关闭，零副作用。
 * </p>
 */
@Slf4j
@Component
public class CustomerRelationMigrationRunner implements ApplicationRunner {

    private final CustomerTransferQrBackfillService backfillService;
    private final CustomerSyncService syncService;
    private final boolean enabled;

    public CustomerRelationMigrationRunner(
            CustomerTransferQrBackfillService backfillService,
            CustomerSyncService syncService,
            @Value("${app.customer-relation-migrate.enabled:false}") boolean enabled) {
        this.backfillService = backfillService;
        this.syncService = syncService;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try {
            int filled = backfillService.backfill();
            log.info("customer_transfer.qr_code_id 回填完成: {} 行", filled);
        } catch (Exception e) {
            log.error("customer_transfer.qr_code_id 回填失败（可重试，不阻断启动）", e);
        }
        try {
            int employees = syncService.syncOnce();
            log.info("customer_relation 全量同步完成: {} 员工", employees);
        } catch (Exception e) {
            log.error("customer_relation 全量同步失败（可重试，不阻断启动）", e);
        }
    }
}
