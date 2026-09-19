package com.bookstore.qrcode.service;

import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 企微部门名称解析服务。
 * <p>
 * 提供部门 ID → 名称映射的加载，结果用 Spring Cache（{@code departments}）
 * 缓存，避免删除记录页等场景每次页面加载都调用企微 {@code department/list} 接口。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepartmentService {

    private final WecomApiClient wecomApiClient;

    /**
     * 加载企微部门 ID → 名称映射。
     * <p>缓存命中直接返回；失败或结果为空时返回空 Map（且不缓存，下次重试）。</p>
     */
    @Cacheable(value = "departments", unless = "#result == null || #result.isEmpty()")
    public Map<Long, String> loadDeptIdNameMap() {
        try {
            JsonNode deptResp = wecomApiClient.listDepartments(null);
            Map<Long, String> map = new HashMap<>();
            if (deptResp.has("department") && deptResp.get("department").isArray()) {
                for (JsonNode d : deptResp.get("department")) {
                    map.put(d.get("id").asLong(), d.get("name").asText());
                }
            }
            return map;
        } catch (Exception e) {
            log.warn("加载企微部门列表失败", e);
            return Map.of();
        }
    }
}
