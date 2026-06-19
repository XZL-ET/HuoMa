package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.QrAccessLog;
import com.bookstore.qrcode.repository.QrAccessLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 学校自助查询访问日志服务。
 * <p>
 * 异步记录学校端的查看和下载行为，用于等保三级审计和管理后台统计。
 * 日志写入不影响请求响应性能。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class SchoolAccessLogService {

    private final QrAccessLogRepository logRepository;

    /** 记录学校端查看活码 */
    public void logView(Long qrCodeId, HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        String ua = request.getHeader("User-Agent");
        saveAsync(QrAccessLog.Action.view, qrCodeId, ip, ua);
    }

    /** 记录学校端下载活码 */
    public void logDownload(Long qrCodeId, HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        String ua = request.getHeader("User-Agent");
        saveAsync(QrAccessLog.Action.download, qrCodeId, ip, ua);
    }

    /** 记录全局联系人查看 */
    public void logGlobalContactView(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        String ua = request.getHeader("User-Agent");
        saveAsync(QrAccessLog.Action.view, null, ip, ua);
    }

    @Async
    void saveAsync(QrAccessLog.Action action, Long qrCodeId, String ip, String ua) {
        QrAccessLog log = QrAccessLog.builder()
                .qrCodeId(qrCodeId)
                .action(action)
                .channel(QrAccessLog.Channel.school)
                .userIdentity(ip)
                .ipAddress(ip)
                .userAgent(truncate(ua, 512))
                .build();
        logRepository.save(log);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
