package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.DailyReport;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 日报数据访问层。
 * <p>
 * 提供对 daily_report（日报）表的 CRUD 操作和自定义查询。
 * 用于每日自动生成的运营数据汇总报表，包含加好友数、转移数等关键指标。
 * </p>
 *
 * @author Bookstore Dev
 * @since 1.0
 */
public interface DailyReportRepository extends JpaRepository<DailyReport, Long> {

    /**
     * 根据日期查询当天的日报记录。
     * <p>
     * 用于首页或报表模块按日期查看/编辑日报，或定时任务判断某天是否已生成日报。
     * 返回 {@link Optional} 以处理指定日期尚无日报的情况。
     * </p>
     *
     * @param date 日期（不含时间）
     * @return 当天的日报记录，不存在则返回 {@link Optional#empty()}
     */
    Optional<DailyReport> findByDate(LocalDate date);
}
