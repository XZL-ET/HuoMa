package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 员工同步服务 —— 定时从企微 API 全量同步员工通讯录到本地 DB。
 *
 * <p>同步策略：
 * <ol>
 *   <li>每 30 分钟自动执行一次全量同步</li>
 *   <li>新员工 → 插入；已有员工 → 更新姓名/部门</li>
 *   <li>已不在企微通讯录中的员工 → 标记为离职（{@code active = false}），不删除记录</li>
 * </ol>
 *
 * @author Bookstore Dev
 * @since 1.4.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeSyncService {

    private final EmployeeRepository employeeRepo;
    private final WecomApiClient wecomApi;

    /**
     * 定时全量同步 — 每 30 分钟执行一次（偏移 7 分钟避免整点争抢资源）。
     */
    @Scheduled(cron = "0 7,37 * * * *")
    public void scheduledSync() {
        log.info("定时员工同步开始");
        try {
            int count = syncFromWecom();
            log.info("定时员工同步完成: {} 人", count);
        } catch (Exception e) {
            log.error("定时员工同步失败", e);
        }
    }

    /**
     * 全量同步企微员工到本地 DB。
     *
     * @return 同步的员工总数
     */
    @Transactional
    public int syncFromWecom() {
        JsonNode resp = wecomApi.getUserSimplelist();

        if (!resp.has("userlist") || !resp.get("userlist").isArray()) {
            log.warn("企微员工列表为空或格式异常");
            return 0;
        }

        List<String> activeUserIds = new ArrayList<>();
        int inserted = 0;
        int updated = 0;

        for (JsonNode u : resp.get("userlist")) {
            String userid = u.get("userid").asText();
            String name = u.has("name") ? u.get("name").asText() : "";
            String dept = u.has("department") ? u.get("department").toString() : null;

            if (userid.isEmpty()) continue;
            activeUserIds.add(userid);

            Optional<Employee> existing = employeeRepo.findByUserid(userid);
            if (existing.isPresent()) {
                Employee emp = existing.get();
                boolean changed = false;
                if (!name.equals(emp.getName())) {
                    emp.setName(name);
                    changed = true;
                }
                if (!emp.getActive()) {
                    emp.setActive(true);
                    changed = true;
                }
                // department 可能变化
                if (dept != null && !dept.equals(emp.getDepartment())) {
                    emp.setDepartment(dept);
                    changed = true;
                }
                if (changed) {
                    emp.setLastSyncTime(LocalDateTime.now());
                    employeeRepo.save(emp);
                    updated++;
                }
            } else {
                Employee emp = Employee.builder()
                    .userid(userid)
                    .name(name)
                    .department(dept)
                    .active(true)
                    .lastSyncTime(LocalDateTime.now())
                    .build();
                employeeRepo.save(emp);
                inserted++;
            }
        }

        // 标记已不在企微通讯录中的员工为离职
        int deactivated = 0;
        if (!activeUserIds.isEmpty()) {
            deactivated = employeeRepo.deactivateNotIn(activeUserIds);
        }

        log.info("员工同步结果: 新增{}人, 更新{}人, 标记离职{}人, 在职共{}人",
            inserted, updated, deactivated, activeUserIds.size());

        return activeUserIds.size();
    }
}
