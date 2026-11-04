package com.bookstore.qrcode.dto;

import java.util.List;

/**
 * 部门准入「预览影响」结果。
 *
 * <p>只读计算把白名单切换为指定部门后的影响面，供管理员确认后再调用 apply。
 * 预览与执行之间数据可能变化，实际结果以 apply 返回为准。</p>
 *
 * @param active               应用后白名单是否启用
 * @param poolRemovals         将被移出全局员工池的员工
 * @param qrRemovals           将从活码撤下接待员的活码及明细（含已满 full 的接待员，名称后缀「（已满）」）
 * @param noDepartmentEmployees 本地无员工记录或部门为空的员工
 * @param serviceBindingsExempt 因服务老师角色豁免、不会被撤下的活跃绑定数
 * @param warnings             预览阶段提示（补位不可行、无部门员工等）
 * @author Bookstore Dev
 * @since 2026-11-02
 */
public record DepartmentWhitelistPreview(
    boolean active,
    List<PoolRemoval> poolRemovals,
    List<QrRemoval> qrRemovals,
    List<NoDeptEmployee> noDepartmentEmployees,
    int serviceBindingsExempt,
    List<String> warnings) {

    /** 将被移出员工池的成员。 */
    public record PoolRemoval(String userid, String name, Long departmentId, String poolStatus) {}

    /** 单个活码的撤人预览。 */
    public record QrRemoval(Long qrCodeId, String schoolName, List<Member> members,
                            boolean needsBackfill, boolean poolCanBackfill) {}

    /** 将从活码撤下的接待员。 */
    public record Member(String userid, String name, Long departmentId) {}

    /** 本地无部门信息的员工。 */
    public record NoDeptEmployee(String userid, String name) {}
}
