package com.bookstore.qrcode.dto;

import lombok.Data;

@Data
public class QrCodeCreateRequest {
    private String schoolName;
    private String schoolId;
    private String regionCity;
    private String regionDistrict;
    private String remark;

    /** 服务老师企微账号 — 活码主联系人（简化输入） */
    private String serviceTeacherUserid;
    /** 服务老师日接上限，默认 1000 */
    private Integer serviceDailyMax;
    /** 接待员企微账号 — 后备池（简化输入，逗号分隔多个） */
    private String receptionistUserid;
    /** 接待员 JSON（高级用法，覆盖 receptionistUserid） */
    private String agentsJson;
    /** 服务老师 JSON（高级用法，覆盖 serviceTeacherUserid） */
    private String serviceTeacherJson;
    /** 后备接待员 JSON: ["wang","zhao"] */
    private String backupsJson;
    /** 欢迎语文案 */
    private String welcomeText;
    /** 收集表单 JSON */
    private String collectFormJson;
    /** 自定义标签，逗号分隔（如 "VIP,重点校,高三优先"），客户扫码后自动打标 */
    private String customTags;
}
