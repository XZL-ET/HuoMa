package com.bookstore.qrcode.dto;

import lombok.Data;
import java.util.List;

/**
 * 批量回收接待员请求。
 */
@Data
public class BatchRecycleRequest {
    /** 要回收的 QrAgent ID 列表 */
    private List<Long> agentIds;
}
