package com.bookstore.qrcode.dto;

/**
 * 部门树扁平化选项，供后台部门准入页的多选列表展示。
 *
 * @param id              企微部门 ID
 * @param name            部门名称
 * @param depth           在部门树中的层级，根部门为 0
 * @param descendantCount 子孙部门总数（不含自身）
 * @author Bookstore Dev
 * @since 2026-11-02
 */
public record DeptOption(Long id, String name, int depth, int descendantCount) {
}
