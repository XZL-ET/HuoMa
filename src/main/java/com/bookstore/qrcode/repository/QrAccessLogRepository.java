package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.QrAccessLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface QrAccessLogRepository extends JpaRepository<QrAccessLog, Long> {

    /** 统计指定活码的学校自助查看次数 */
    @Query("SELECT COUNT(a) FROM QrAccessLog a WHERE a.qrCodeId = :qrCodeId " +
           "AND a.channel = 'school' AND a.action = 'view'")
    long countSchoolViewsByQrCodeId(@Param("qrCodeId") Long qrCodeId);

    /** 统计指定活码的学校自助下载次数 */
    @Query("SELECT COUNT(a) FROM QrAccessLog a WHERE a.qrCodeId = :qrCodeId " +
           "AND a.channel = 'school' AND a.action = 'download'")
    long countSchoolDownloadsByQrCodeId(@Param("qrCodeId") Long qrCodeId);

    /** 按渠道分页查询日志 */
    @Query("SELECT a FROM QrAccessLog a WHERE " +
           "(:channel IS NULL OR a.channel = :channel) " +
           "AND (:qrCodeId IS NULL OR a.qrCodeId = :qrCodeId) " +
           "ORDER BY a.accessedAt DESC")
    Page<QrAccessLog> findByFilters(@Param("channel") String channel,
                                     @Param("qrCodeId") Long qrCodeId,
                                     Pageable pageable);

    /** 统计学校自助查询入口的总访问量（以首页view计） */
    long countByChannel(QrAccessLog.Channel channel);
}
