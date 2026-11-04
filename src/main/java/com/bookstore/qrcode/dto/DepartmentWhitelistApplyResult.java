package com.bookstore.qrcode.dto;

import java.util.List;

/**
 * 部门准入「确认应用」结果。
 *
 * @param allowedDeptCount             本次应用的白名单根部门数（去重）
 * @param poolRemoved                  被移出全局员工池的人数
 * @param activeReceptionistsRemoved   从活码撤下的 active 接待员数
 * @param fullReceptionistsRemoved     从活码撤下的 full 接待员数
 * @param backfilled                   从员工池补位到活码的人数
 * @param qrCodesProcessed             实际执行撤人编排的活码数
 * @param qrCodesSkipped               因轮换锁冲突或池枯竭而跳过的活码数
 * @param failures                     跳过/失败明细
 * @author Bookstore Dev
 * @since 2026-11-02
 */
public record DepartmentWhitelistApplyResult(
    int allowedDeptCount, int poolRemoved,
    int activeReceptionistsRemoved, int fullReceptionistsRemoved,
    int backfilled, int qrCodesProcessed, int qrCodesSkipped,
    List<String> failures) {}
