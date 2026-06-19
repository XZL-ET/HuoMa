package com.bookstore.qrcode.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 市州列表项：城市名 + 下辖区县数量 */
@Data
@AllArgsConstructor
public class SchoolCityDTO {
    private String cityName;
    private long districtCount;
}
