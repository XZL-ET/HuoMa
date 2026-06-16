package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.DailyReport;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
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
     *
     * @param date 日期（不含时间）
     * @return 当天的日报记录，不存在则返回 {@link Optional#empty()}
     */
    Optional<DailyReport> findByDate(LocalDate date);

    /**
     * 查询日期范围内的日报记录，按日期升序。
     * 用于趋势图（7 天 / 30 天）和 Excel 导出。
     *
     * @param start 起始日期（含）
     * @param end   结束日期（含）
     * @return 日期范围内的日报列表，按日期升序
     */
    List<DailyReport> findByDateBetweenOrderByDateAsc(LocalDate start, LocalDate end);

    /**
     * 查询最新一条日报记录的日期。
     * 用于判断 DailyReport 数据覆盖范围。
     *
     * @return 最新的日报记录
     */
    Optional<DailyReport> findFirstByOrderByDateDesc();
}
