package com.bookstore.qrcode.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 活码树节点 DTO — 仅包含树接口渲染所需的 5 个字段，
 * 替代 {@code findAll()} 加载全部 25 列的浪费。
 *
 * @author Bookstore Dev Team
 * @since 2.0
 */
@Data
@AllArgsConstructor
public class QrCodeTreeDto {
    private Long id;
    private String schoolName;
    private String schoolId;
    private String regionCity;
    private String regionDistrict;
    private Long groupId;
}
