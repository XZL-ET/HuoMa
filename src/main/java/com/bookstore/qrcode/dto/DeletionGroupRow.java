package com.bookstore.qrcode.dto;

import com.bookstore.qrcode.entity.CustomerDeletionEvent;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 删除记录列表的聚合行：同一「客户 + 员工 + 方向」在所选时间窗内的多次删除合并为一行。
 *
 * @param count           该关系在窗口内的删除次数
 * @param firstDeletedAt  最早一次删除时间
 * @param lastDeletedAt   最近一次删除时间
 * @param times           每次删除时间，倒序，供展开查看明细
 */
public record DeletionGroupRow(
        String externalUserid,
        String userid,
        CustomerDeletionEvent.Direction direction,
        long count,
        LocalDateTime firstDeletedAt,
        LocalDateTime lastDeletedAt,
        List<LocalDateTime> times) {
}
