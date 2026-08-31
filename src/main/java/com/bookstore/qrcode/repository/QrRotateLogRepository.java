package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.QrRotateLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 二维码轮换日志数据访问层。
 * <p>
 * 提供对 qr_rotate_log 表的 CRUD 操作和自定义查询。
 * 轮换日志记录每次活码切换到新二维码的时间、来源和目标二维码信息，
 * 用于运营审计和问题排查。</p>
 *
 * @author Bookstore Dev Team
 * @since 1.0.0
 */
public interface QrRotateLogRepository extends JpaRepository<QrRotateLog, Long> {

    /**
     * 根据活码 ID 查询轮换日志，按创建时间降序排列（最新的在前），并支持分页。
     * <p>
     * 用于在管理后台展示某个活码的二维码轮换历史记录，
     * 方便运营人员查看各时段的轮换情况。</p>
     *
     * @param qrCodeId 活码 ID
     * @param pageable 分页参数（页码、每页条数、排序方式等）
     * @return 符合条件的轮换日志列表，按 {@code createdAt} 降序排列
     */
    List<QrRotateLog> findByQrCodeIdOrderByCreatedAtDesc(Long qrCodeId, Pageable pageable);

    /**
     * 按创建时间范围查询轮换日志，按时间倒序（最新在前）。
     * <p>用于全局轮换日志列表页，跨所有活码查看下码/上码记录，
     * 支持分页截断避免一次加载过多。</p>
     *
     * @param start    起始时间（含）
     * @param end      结束时间（含）
     * @param pageable 分页参数（页码、每页条数）
     * @return 时间范围内的轮换日志，按 {@code createdAt} 降序
     */
    List<QrRotateLog> findByCreatedAtBetweenOrderByCreatedAtDesc(
        LocalDateTime start, LocalDateTime end, Pageable pageable);
}
