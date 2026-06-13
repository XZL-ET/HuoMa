package com.bookstore.qrcode.dto;

import lombok.Data;

/**
 * 创建活码（群活码/单人活码）请求 DTO。
 * <p>
 * 接收客户端提交的表单数据，包含学校信息、服务老师配置、
 * 接待员池、欢迎语、表单收集和自定义标签等字段。
 * 支持"简化输入"（直接填写企微账号 userid）和"高级输入"
 * （通过 JSON 字符串传递结构化数据）两种模式，
 * 高级模式会覆盖简化输入的同名字段。
 * </p>
 *
 * @author Bookstore Dev Team
 * @since 1.0.0
 */
@Data
public class QrCodeCreateRequest {

    // ==================== 学校基本信息 ====================

    /** 学校名称，用于标识活码所属学校 */
    private String schoolName;

    /** 学校唯一标识（如教育局编号或系统内部 ID），用于后续数据关联 */
    private String schoolId;

    /** 学校所在城市，用于区域化分拣和统计 */
    private String regionCity;

    /** 学校所在区/县，用于更精细的区域运营管理 */
    private String regionDistrict;

    /** 学校学生人数，用于自动计算所需接待员数量（每120学生配1人，最少1人，最多100人） */
    private Integer studentCount;

    /** 备注信息，灵活存储额外描述或内部说明 */
    private String remark;

    /** 在职继承目标员工 userid（手动触发继承时的导入对象） */
    private String transferTargetUserid;

    /** 初始上码员工数，默认 1 */
    private Integer initialAgentCount;

    /** 初始上码员工 userid 列表（逗号分隔，如 "zhangsan,lisi"） */
    private String initialAgentUserids;

    // ==================== 服务老师（主联系人）配置 ====================

    /**
     * 服务老师企业微信账号（userid）&mdash; 简化输入模式。
     * <p>
     * 活码的主联系人，客户扫码后会首先分配给该老师。
     * 直接填写服务老师在企微中的 userid 即可，
     * 与 {@link #serviceTeacherJson} 二选一；同时提供时后者优先。
     * </p>
     */
    private String serviceTeacherUserid;

    /**
     * 服务老师每日添加客户上限，默认值 1000。
     * <p>
     * 当服务老师当日添加客户数达到此上限后，
     * 新增客户将自动分配给后备接待员，避免超负荷服务。
     * </p>
     */
    private Integer serviceDailyMax;

    // ==================== 接待员池配置 ====================

    /**
     * 接待员企业微信账号（userid）&mdash; 简化输入模式。
     * <p>
     * 后备接待员池，多个账号用英文逗号分隔
     * （如 "zhangsan,lisi,wangwu"）。
     * 当服务老师不在线或达上限时，客户会轮流分配给池中的接待员。
     * 与 {@link #agentsJson} 二选一；同时提供时后者优先。
     * </p>
     */
    private String receptionistUserid;

    /**
     * 接待员 JSON 配置 &mdash; 高级输入模式。
     * <p>
     * 使用 JSON 数组传递完整的接待员配置，
     * 可设置每个接待员的独立属性（如权重、日上限等）。
     * 提供此参数时将覆盖 {@link #receptionistUserid} 的值。
     * </p>
     */
    private String agentsJson;

    /**
     * 服务老师 JSON 配置 &mdash; 高级输入模式。
     * <p>
     * 使用 JSON 字符串传递服务老师的结构化信息，
     * 支持设置更详细的属性。提供此参数时将覆盖
     * {@link #serviceTeacherUserid} 的值。
     * </p>
     */
    private String serviceTeacherJson;

    /**
     * 后备接待员 JSON 配置。
     * <p>
     * JSON 数组格式，如：<code>["wang","zhao"]</code>。
     * 当主接待员池全部达上限或不可用时，作为最终兜底方案。
     * </p>
     */
    private String backupsJson;

    // ==================== 客户侧配置 ====================

    /**
     * 客户扫码后发送的欢迎语文案。
     * <p>
     * 支持文本消息，可包含占位符用于动态替换。
     * 留空则使用系统默认欢迎语。
     * </p>
     */
    private String welcomeText;

    /**
     * 客户信息收集表单 JSON 配置。
     * <p>
     * 定义需要客户扫码后填写的表单字段，
     * 如姓名、手机号、年级等，格式为 JSON。
     * 不提供则不开启表单收集。
     * </p>
     */
    private String collectFormJson;

    /**
     * 自定义标签，英文逗号分隔。
     * <p>
     * 示例：<code>"VIP,重点校,高三优先"</code>
     * <br>客户扫码添加好友成功后，系统自动为客户打上这些标签，
     * 方便后续客户分层运营和精准触达。
     * </p>
     */
    private String customTags;
}
