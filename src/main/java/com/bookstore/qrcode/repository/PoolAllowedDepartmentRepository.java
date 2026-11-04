package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.PoolAllowedDepartment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 全局员工池部门准入白名单数据访问层。
 *
 * @author Bookstore Dev
 * @since 2026-11-02
 */
public interface PoolAllowedDepartmentRepository
        extends JpaRepository<PoolAllowedDepartment, Long> {

    /** 全量查询，按部门 ID 升序 */
    List<PoolAllowedDepartment> findAllByOrderByDeptIdAsc();
}
