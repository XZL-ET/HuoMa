package com.bookstore.qrcode.service;

/**
 * 转接失败原因归类。
 *
 * <p>转接明细页「失败原因分布」与「错误类型下钻」的唯一归类事实来源：
 * {@link #classify} 用于内存统计，{@link #patternFor} 用于 DB 层 LIKE 过滤，
 * 二者同源于每个枚举值的 {@code keyword}。</p>
 *
 * <p>归类优先级即枚举声明顺序：先匹配具体错误码/关键词，最后兜底 {@link #OTHER}。
 * 空白或 null 的失败原因不属于任何类别（返回 null，不计入分布）。</p>
 */
public enum TransferFailType {

    /** 40205 — 接管员工企微票据过期 */
    TICKET_EXPIRED("40205", "票据过期", "errcode=40205"),
    /** 84061 — 客户已不是好友 */
    NOT_FRIEND("84061", "已不是好友", "errcode=84061"),
    /** 84073 — 客户已删除服务人员 */
    DELETED_BY_USER("84073", "客户已删除", "errcode=84073"),
    /** 84096 — 客户无法发起在职继承 */
    TRANSFER_NOT_AVAILABLE("84096", "无法发起继承", "errcode=84096"),
    /** 84097 — 接替成员客户数已达上限（有码与无码文案均含「客户数已达上限」） */
    LIMIT_EXCEEDED("84097", "客户数达上限", "客户数已达上限"),
    /** 84100 — 已有正在继承的员工 */
    PENDING_EXISTS("84100", "已有继承中", "errcode=84100"),
    /** 45035 — 操作冲突 */
    CONFLICT("45035", "操作冲突", "errcode=45035"),
    /** 无错误码的「超时作废」类文案 */
    TIMEOUT("timeout", "超时作废", "超时"),
    /** 客户拒绝接替（企微 status=3，无错误码） */
    REJECTED("rejected", "客户拒绝", "客户拒绝接替"),
    /** 其他非空原因 */
    OTHER("other", "其他", null);

    private final String key;
    private final String label;
    private final String keyword;

    TransferFailType(String key, String label, String keyword) {
        this.key = key;
        this.label = label;
        this.keyword = keyword;
    }

    public String getKey() {
        return key;
    }

    public String getLabel() {
        return label;
    }

    /** 分类关键词（内存 contains 匹配与 DB LIKE 过滤的单一事实来源），无关键词（OTHER）返回 null */
    public String keyword() {
        return keyword;
    }

    /** 关键词对应的 SQL LIKE 模式（{@code %keyword%}），无关键词（OTHER）返回 null */
    public String likePattern() {
        return keyword == null ? null : "%" + keyword + "%";
    }

    /**
     * 按声明顺序（即优先级）归类失败原因。
     *
     * @return 匹配的类别；null/空白返回 null；无匹配返回 {@link #OTHER}
     */
    public static TransferFailType classify(String failReason) {
        if (failReason == null || failReason.isBlank()) {
            return null;
        }
        for (TransferFailType t : values()) {
            if (t.keyword != null && failReason.contains(t.keyword)) {
                return t;
            }
        }
        return OTHER;
    }

    /** 按 key 查找类别（用于校验 URL 参数），未知 key 返回 null */
    public static TransferFailType fromKey(String key) {
        if (key == null) {
            return null;
        }
        for (TransferFailType t : values()) {
            if (t.key.equals(key)) {
                return t;
            }
        }
        return null;
    }

    /** 按 key 返回对应的 LIKE 模式，未知 key 返回 null */
    public static String patternFor(String key) {
        TransferFailType t = fromKey(key);
        return t == null ? null : t.likePattern();
    }

    /** 判断 key 是否为「其他」补集类别（用于下钻时切换排除式查询） */
    public static boolean isOther(String key) {
        return OTHER.key.equals(key);
    }
}
