package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.QrBackupPool;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/**
 * 二维码备份池数据访问层。
 * <p>
 * 提供对 qr_backup_pool 表的 CRUD 操作和自定义查询。
 * 备份池用于存储活码的备用二维码，当主码达到日接上限或失效时，
 * 系统自动从备份池中选取下一个可用二维码进行轮换。</p>
 *
 * @author Bookstore Dev Team
 * @since 1.0.0
 */
public interface QrBackupPoolRepository extends JpaRepository<QrBackupPool, Long> {

    /**
     * 根据活码 ID 查询所有备份池记录。
     *
     * @param qrCodeId 活码 ID
     * @return 该活码关联的所有备份池记录列表
     */
    List<QrBackupPool> findByQrCodeId(Long qrCodeId);

    /**
     * 根据活码 ID 和状态查询备份池记录，并按排序字段升序排列。
     * <p>
     * 用于获取某个活码在指定状态下的有序备份二维码列表，
     * 排序字段 {@code sortOrder} 控制二维码的轮换优先级。</p>
     *
     * @param qrCodeId 活码 ID
     * @param status   备份池状态（如可用、已使用、已失效等）
     * @return 符合条件的备份池记录列表，按 {@code sortOrder} 升序排列
     */
    List<QrBackupPool> findByQrCodeIdAndStatusOrderBySortOrder(
            Long qrCodeId, QrBackupPool.PoolStatus status);

    /**
     * 统计指定活码 ID 和状态下的备份池记录数量。
     * <p>
     * 用于判断某个活码目前还有多少可用的备用二维码，
     * 当可用数量不足时可触发告警或补充备份。</p>
     *
     * @param qrCodeId 活码 ID
     * @param status   备份池状态
     * @return 符合条件的记录总数
     */
    long countByQrCodeIdAndStatus(Long qrCodeId, QrBackupPool.PoolStatus status);
}
