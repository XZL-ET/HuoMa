package com.bookstore.qrcode.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 区县列表项：区县名 + 下辖学校数量 */
@Data
@AllArgsConstructor
public class SchoolDistrictDTO {
    private String districtName;
    private long schoolCount;
}
