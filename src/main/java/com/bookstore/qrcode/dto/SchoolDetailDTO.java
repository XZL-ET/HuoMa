package com.bookstore.qrcode.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 学校详情（供模板渲染）。
 * 合并 school 表 + qr_code 表 + district_manager 表的数据。
 */
@Data
@Builder
public class SchoolDetailDTO {
    // 学校信息
    private String schoolId;
    private String schoolName;
    private String regionCity;
    private String regionDistrict;

    // 活码状态
    private boolean hasQrcode;          // 是否有活码记录
    private String qrStatus;            // 活码状态: active/paused/full/no_agent/null
    private String qrUrl;               // 活码图片 URL（active 时有效）

    // 联系人信息（有活码时关联的接待老师，取第一个 active 的 service agent name）
    private String contactName;

    // 兜底信息（无活码或非 active 时）
    private String fallbackManagerName;    // 区县负责人姓名
    private String fallbackQrUrl;          // 区县负责人活码 URL
    private boolean isGlobalFallback;      // true=使用了全局联系人兜底

    // 状态标签文案
    public String getStatusLabel() {
        if (hasQrcode) {
            if ("active".equals(qrStatus)) return "活码已就绪";
            if ("paused".equals(qrStatus)) return "活码维护中";
            if ("full".equals(qrStatus)) return "咨询人数较多";
            if ("no_agent".equals(qrStatus)) return "暂未分配接待人员";
            return "活码暂不可用";
        }
        return "活码尚未创建";
    }

    public boolean isQrAvailable() {
        return hasQrcode && "active".equals(qrStatus) && qrUrl != null && !qrUrl.isEmpty();
    }
}
