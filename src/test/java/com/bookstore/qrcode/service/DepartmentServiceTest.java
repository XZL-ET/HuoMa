package com.bookstore.qrcode.service;

import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DepartmentService 部门名称解析")
class DepartmentServiceTest {

    @Mock private WecomApiClient wecomApiClient;
    @InjectMocks private DepartmentService departmentService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("解析部门列表为 id → 名称映射")
    void parsesDepartmentList() throws Exception {
        when(wecomApiClient.listDepartments(null)).thenReturn(objectMapper.readTree(
                "{\"department\":[{\"id\":1,\"name\":\"运营部\"},{\"id\":3,\"name\":\"客服部\"}]}"));

        Map<Long, String> map = departmentService.loadDeptIdNameMap();

        assertThat(map).containsEntry(1L, "运营部").containsEntry(3L, "客服部");
    }

    @Test
    @DisplayName("API 失败时返回空 Map")
    void returnsEmptyOnApiFailure() throws Exception {
        when(wecomApiClient.listDepartments(null)).thenThrow(new RuntimeException("api down"));

        Map<Long, String> map = departmentService.loadDeptIdNameMap();

        assertThat(map).isEmpty();
    }

    @Test
    @DisplayName("响应缺少 department 字段时返回空 Map")
    void returnsEmptyWhenNoDepartmentField() throws Exception {
        when(wecomApiClient.listDepartments(null)).thenReturn(objectMapper.readTree("{}"));

        Map<Long, String> map = departmentService.loadDeptIdNameMap();

        assertThat(map).isEmpty();
    }
}
