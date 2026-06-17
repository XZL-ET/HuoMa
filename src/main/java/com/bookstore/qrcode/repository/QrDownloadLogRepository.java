package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.QrDownloadLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 下载日志数据访问层。
 * <p>
 * 提供对 qr_download_log（活码下载日志）表的 CRUD 操作和自定义查询。
 * 用于记录和统计企业微信员工下载活码的行为，支持按活码、按员工等维度查询。
 * </p>
 *
 * @author Bookstore Dev
 * @since 1.0
 */
public interface QrDownloadLogRepository extends JpaRepository<QrDownloadLog, Long> {

    /**
     * 统计某员工下载某活码的总次数。
     *
     * @param qrCodeId    活码 ID
     * @param agentUserid 员工的企业微信用户 ID
     * @return 下载总次数
     */
    @Query("SELECT COUNT(d) FROM QrDownloadLog d WHERE d.qrCodeId = :qrCodeId AND d.agentUserid = :agentUserid")
    long countByQrCodeIdAndAgentUserid(@Param("qrCodeId") Long qrCodeId,
                                       @Param("agentUserid") String agentUserid);

    /**
     * 查询某员工是否有某活码的下载记录。
     *
     * @param qrCodeId    活码 ID
     * @param agentUserid 员工的企业微信用户 ID
     * @return true 如果存在下载记录
     */
    @Query("SELECT COUNT(d) > 0 FROM QrDownloadLog d WHERE d.qrCodeId = :qrCodeId AND d.agentUserid = :agentUserid")
    boolean existsByQrCodeIdAndAgentUserid(@Param("qrCodeId") Long qrCodeId,
                                           @Param("agentUserid") String agentUserid);

    /**
     * 查询某员工的下载历史，按下载时间倒序排列。
     * <p>
     * 用于员工个人中心展示其最近下载的活码记录。
     * </p>
     *
     * @param agentUserid 员工的企业微信用户 ID
     * @return 下载日志列表（按时间倒序）
     */
    List<QrDownloadLog> findByAgentUseridOrderByDownloadedAtDesc(String agentUserid);

    /**
     * 查询某活码的全部下载记录。
     * <p>
     * 用于查看某个活码被哪些员工下载过。
     * </p>
     *
     * @param qrCodeId 活码 ID
     * @return 该活码的下载日志列表
     */
    List<QrDownloadLog> findByQrCodeId(Long qrCodeId);

    /**
     * 统计某活码的下载总次数。
     *
     * @param qrCodeId 活码 ID
     * @return 下载总次数
     */
    long countByQrCodeId(Long qrCodeId);

    /**
     * 批量查询一批活码的下载记录。
     * <p>
     * 供统计页面一次性查询多个活码的下载人员信息。
     * </p>
     *
     * @param qrCodeIds 活码 ID 列表
     * @return 匹配的下载日志列表
     */
    List<QrDownloadLog> findByQrCodeIdIn(List<Long> qrCodeIds);
}
