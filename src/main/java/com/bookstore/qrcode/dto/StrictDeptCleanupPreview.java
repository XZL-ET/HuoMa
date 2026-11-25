package com.bookstore.qrcode.dto;

import java.util.List;
import java.util.Set;

/**
 * 严格同部门清退预览（只读快照）。
 *
 * <p>由 {@code StrictDeptCleanupService.preview} 生成，供确认页展示：
 * 严格开关与豁免名单、涉及活码/接待员总数、按区县分组，以及逐码处理计划。
 * 预览不代表最终结果——apply 时会按当时库状态重新计算（见 R13）。</p>
 *
 * @param strictModeEnabled 当前严格同部门开关
 * @param exemptUserids     当前豁免服务老师 userid 集合
 * @param codeCount         可处理活码数（不含被跳过的码）
 * @param receptionistCount 可处理码上待撤接待员总数（active + full）
 * @param districts         按区县汇总的待清退影响面
 * @param codes             逐码处理计划；被跳过的码也在列表中且 {@code skipReason} 非空
 * @param warnings          不影响整体执行的提示（跳过原因、缺失档案等）
 * @author Bookstore Dev
 * @since 2026-11-25
 */
public record StrictDeptCleanupPreview(
    boolean strictModeEnabled,
    Set<String> exemptUserids,
    int codeCount,
    int receptionistCount,
    List<DistrictGroup> districts,
    List<QrPlan> codes,
    List<String> warnings) {

    /**
     * 单区县影响面。
     *
     * @param district    区县名（活码 regionDistrict 为空时为「未知」）
     * @param codeCount   该区县可处理活码数
     * @param memberCount 该区县待撤接待员数
     */
    public record DistrictGroup(String district, int codeCount, int memberCount) {}

    /**
     * 单活码处理计划。
     *
     * @param qrCodeId            活码 ID
     * @param schoolName          学校名称
     * @param district            区县（空时为「未知」）
     * @param members             待撤接待员；跳过或无需处理的码为空列表
     * @param needsBackfill       撤完 active 接待员后是否不足 1 名 active 成员
     * @param localStandbyAvailable 只读口径下本县池是否还有可补人员
     * @param skipReason          跳过原因；可处理时为 {@code null}
     */
    public record QrPlan(
        Long qrCodeId,
        String schoolName,
        String district,
        List<Member> members,
        boolean needsBackfill,
        boolean localStandbyAvailable,
        String skipReason) {}

    /**
     * 待撤接待员。
     *
     * @param userid        企微 userid
     * @param name          员工姓名（档案缺失时回退 userid）
     * @param primaryDeptId 员工部门 JSON 首元素，解析失败为 {@code null}
     * @param exempt        恒为 false（保持接口形状，豁免人员不会进入待撤列表）
     */
    public record Member(String userid, String name, Long primaryDeptId, boolean exempt) {}
}
