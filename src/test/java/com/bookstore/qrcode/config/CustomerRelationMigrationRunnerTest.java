package com.bookstore.qrcode.config;

import com.bookstore.qrcode.service.CustomerSyncService;
import com.bookstore.qrcode.service.CustomerTransferQrBackfillService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("CustomerRelationMigrationRunner 一次性迁移触发")
class CustomerRelationMigrationRunnerTest {

    @Test
    @DisplayName("开关关闭时不触发任何迁移")
    void disabledDoesNothing() throws Exception {
        CustomerTransferQrBackfillService backfill = mock(CustomerTransferQrBackfillService.class);
        CustomerSyncService sync = mock(CustomerSyncService.class);
        CustomerRelationMigrationRunner runner =
                new CustomerRelationMigrationRunner(backfill, sync, false);

        runner.run(null);

        verifyNoInteractions(backfill, sync);
    }

    @Test
    @DisplayName("开关开启时先回填 qr_code_id 再全量同步")
    void enabledRunsBackfillThenSync() throws Exception {
        CustomerTransferQrBackfillService backfill = mock(CustomerTransferQrBackfillService.class);
        CustomerSyncService sync = mock(CustomerSyncService.class);
        when(backfill.backfill()).thenReturn(3);
        when(sync.syncOnce()).thenReturn(42);
        CustomerRelationMigrationRunner runner =
                new CustomerRelationMigrationRunner(backfill, sync, true);

        runner.run(null);

        InOrder order = inOrder(backfill, sync);
        order.verify(backfill).backfill();
        order.verify(sync).syncOnce();
    }

    @Test
    @DisplayName("迁移失败不抛异常、不阻断启动")
    void migrationFailureDoesNotBlockStartup() throws Exception {
        CustomerTransferQrBackfillService backfill = mock(CustomerTransferQrBackfillService.class);
        CustomerSyncService sync = mock(CustomerSyncService.class);
        when(backfill.backfill()).thenThrow(new RuntimeException("db down"));
        when(sync.syncOnce()).thenReturn(1);
        CustomerRelationMigrationRunner runner =
                new CustomerRelationMigrationRunner(backfill, sync, true);

        runner.run(null); // 不应抛出异常

        verify(sync).syncOnce(); // 回填失败后仍继续全量同步
    }
}
