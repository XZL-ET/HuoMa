package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.QrAccessLog;
import com.bookstore.qrcode.repository.QrAccessLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    @Transactional
    public void logView(Long qrCodeId, HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        String ua = request.getHeader("User-Agent");
        QrAccessLog log = QrAccessLog.builder()
                .qrCodeId(qrCodeId)
                .action(QrAccessLog.Action.view)
                .channel(QrAccessLog.Channel.school)
                .userIdentity(ip)
                .ipAddress(ip)
                .userAgent(truncate(ua, 512))
                .build();
        logRepository.save(log);
    }

    /** 记录学校端下载活码 */
    @Transactional
    public void logDownload(Long qrCodeId, HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        String ua = request.getHeader("User-Agent");
        QrAccessLog log = QrAccessLog.builder()
                .qrCodeId(qrCodeId)
                .action(QrAccessLog.Action.download)
                .channel(QrAccessLog.Channel.school)
                .userIdentity(ip)
                .ipAddress(ip)
                .userAgent(truncate(ua, 512))
                .build();
        logRepository.save(log);
    }

    /** 记录全局联系人查看 */
    @Transactional
    public void logGlobalContactView(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        String ua = request.getHeader("User-Agent");
        QrAccessLog log = QrAccessLog.builder()
                .qrCodeId(null)
                .action(QrAccessLog.Action.view)
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
