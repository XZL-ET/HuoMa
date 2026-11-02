package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.entity.PoolAllowedDepartment;
import com.bookstore.qrcode.repository.PoolAllowedDepartmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("部门准入白名单仓储")
class PoolAllowedDepartmentRepositoryTest extends BaseIntegrationTest {

    @Autowired private PoolAllowedDepartmentRepository repo;

    @BeforeEach
    void setUp() { repo.deleteAll(); }

    @Test
    @DisplayName("按 deptId 升序返回，createdAt 自动填充")
    void savesAndOrdersByDeptId() {
        repo.save(PoolAllowedDepartment.builder()
            .deptId(200L).deptName("后勤").createdBy("tester").build());
        repo.save(PoolAllowedDepartment.builder()
            .deptId(100L).deptName("校区").createdBy("tester").build());

        List<PoolAllowedDepartment> rows = repo.findAllByOrderByDeptIdAsc();

        assertThat(rows).extracting(PoolAllowedDepartment::getDeptId)
            .containsExactly(100L, 200L);
        assertThat(rows.get(0).getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("同一 deptId 重复保存为更新，不产生重复行")
    void saveSameDeptIdUpdates() {
        repo.save(PoolAllowedDepartment.builder().deptId(100L).deptName("旧名").build());
        repo.save(PoolAllowedDepartment.builder().deptId(100L).deptName("新名").build());

        assertThat(repo.count()).isEqualTo(1);
        assertThat(repo.findById(100L).orElseThrow().getDeptName()).isEqualTo("新名");
    }
}
