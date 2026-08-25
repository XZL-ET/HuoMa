package com.bookstore.qrcode.service;

import com.bookstore.qrcode.repository.CustomerTransferRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 每日转接对账服务。
 * <p>
 * 对指定日期（通常为昨日）发起的在职继承转接，按区县负责人汇总
 * 成功/失败/进行中等计数，并通过企微应用消息推送给对应负责人，
 * 避免转移失败后"静默不管"导致客户流失。
 * </p>
 *
 * <p>时间口径：{@code CustomerTransfer.transferTime}（转移发起时间）。
 * 关联链：转移记录 → 客户(sourceQrId) → 活码(region) → 区县负责人。</p>
 *
 * @author Bookstore Dev
 * @since 2.x
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferReconciliationService {

    private final CustomerTransferRepository transferRepo;
    private final WecomApiClient wecomApi;

    /**
     * 执行某日期的转接对账，按负责人逐条推送。
     *
     * @param date 对账日期（统计 [date 00:00, date+1 00:00) 内发起的转接）
     * @return 成功推送的负责人数量
     */
    public int reconcile(LocalDate date) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();

        List<Object[]> rows = transferRepo.summarizeTransfersByManager(start, end);
        int sent = 0;
        for (Object[] row : rows) {
            String managerUserid = (String) row[0];
            try {
                wecomApi.sendAppMessage(managerUserid, buildMessage(date, row));
                sent++;
            } catch (Exception e) {
                log.error("转接对账推送失败: date={}, manager={}", date, managerUserid, e);
            }
        }
        log.info("每日转接对账完成: date={}, managers={}, sent={}", date, rows.size(), sent);
        return sent;
    }

    private String buildMessage(LocalDate date, Object[] row) {
        String managerName = (String) row[1];
        long total = ((Number) row[2]).longValue();
        long confirmed = ((Number) row[3]).longValue();
        long rejected = ((Number) row[4]).longValue();
        long timeout = ((Number) row[5]).longValue();
        long apiFailed = ((Number) row[6]).longValue();
        long retryLimit = ((Number) row[7]).longValue();
        long pending = ((Number) row[8]).longValue();
        long failed = rejected + timeout + apiFailed + retryLimit;

        return String.format(
            "【转接对账】%s %s，昨日转接：共 %d 笔，成功 %d，失败 %d"
            + "（拒绝 %d、超时 %d、API失败 %d、重试耗尽 %d），进行中 %d。"
            + "请登录转接记录页查看明细并跟进。",
            date, managerName, total, confirmed, failed,
            rejected, timeout, apiFailed, retryLimit, pending);
    }
}
