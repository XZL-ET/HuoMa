package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.CustomerDeletionEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 客户删除关系事件数据访问层。
 *
 * @author Bookstore Dev
 * @since 2.x
 */
public interface CustomerDeletionEventRepository extends JpaRepository<CustomerDeletionEvent, Long> {

    /**
     * 按删除方向与删除时间区间查询事件，按删除时间升序排列。
     * 用于每日日报：查询昨日的「客户删除员工」事件。
     *
     * @param direction 删除方向（如 CUSTOMER_DELETED_AGENT）
     * @param start     时间区间起始（含）
     * @param end       时间区间结束（不含）
     * @return 满足条件的事件列表
     */
    List<CustomerDeletionEvent> findByDirectionAndDeletedAtBetween(
        CustomerDeletionEvent.Direction direction, LocalDateTime start, LocalDateTime end);

    /**
     * 按删除时间区间查询事件（不限方向），按删除时间倒序，并支持分页截断。
     * 用于后台「删除记录」列表页：跨方向查看全部删除关系记录，最新在前。
     *
     * @param start    时间区间起始（含）
     * @param end      时间区间结束（含）
     * @param pageable 分页参数（用于限制返回条数）
     * @return 时间区间内的事件列表，按删除时间倒序
     */
    List<CustomerDeletionEvent> findByDeletedAtBetweenOrderByDeletedAtDesc(
        LocalDateTime start, LocalDateTime end, Pageable pageable);

    /**
     * 按删除时间区间查询事件（不限方向、不限条数），按删除时间倒序。
     * 用于后台「删除记录」Excel 导出：导出当前筛选条件下的全部记录（不受列表页 500 条截断限制）。
     *
     * @param start 时间区间起始（含）
     * @param end   时间区间结束（含）
     * @return 时间区间内的事件列表，按删除时间倒序
     */
    List<CustomerDeletionEvent> findByDeletedAtBetweenOrderByDeletedAtDesc(
        LocalDateTime start, LocalDateTime end);
}
