package com.bookstore.qrcode.dto;

import lombok.Data;

@Data
public class QrCodeCreateRequest {
    private String schoolName;
    private String schoolId;
    private String regionCity;
    private String regionDistrict;
    private String remark;

    /** 接待员 JSON: [{"userid":"li","dailyMax":200}] */
    private String agentsJson;
    /** 服务老师 JSON: {"userid":"zhang","serviceDailyMax":1000} */
    private String serviceTeacherJson;
    /** 后备接待员 JSON: ["wang","zhao"] */
    private String backupsJson;
    /** 欢迎语文案 */
    private String welcomeText;
    /** 收集表单 JSON */
    private String collectFormJson;
}
