package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.Employee;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 员工数据访问层。
 *
 * @author Bookstore Dev
 * @since 1.4.0
 */
public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    /** 按企微 userid 精确查找 */
    Optional<Employee> findByUserid(String userid);

    /** 查找全部在职员工，按姓名排序 */
    List<Employee> findAllByActiveTrueOrderByName();

    /** 查找全部离职员工（active=false），仅返回 userid 用于清理 */
    List<Employee> findByActiveFalse();

    /** 按姓名模糊搜索 */
    List<Employee> findByNameContaining(String keyword);

    /**
     * 将指定 userid 之外的员工标记为离职。
     * 用在全量同步后批量清理已不在企微通讯录中的员工记录。
     */
    @Modifying
    @Transactional
    @Query("UPDATE Employee e SET e.active = false WHERE e.userid NOT IN :activeUserIds")
    int deactivateNotIn(List<String> activeUserIds);

    /** 按 userid 列表批量查询 — 替代 findAll 全表加载 */
    List<Employee> findByUseridIn(Collection<String> userids);

    // ── 全量视图分页查询（员工管理页用） ──

    /** 全量分页查询所有员工（含离职），按姓名排序 */
    Page<Employee> findAllByOrderByName(Pageable pageable);

    /** 按企微状态分页查询，按姓名排序 */
    Page<Employee> findByWechatStatusOrderByName(Integer wechatStatus, Pageable pageable);

    /** 按 userid 集合分页查询，按姓名排序 */
    Page<Employee> findByUseridInOrderByName(Collection<String> userids, Pageable pageable);

    /** 非分页全量查询（异常筛选路径用） */
    List<Employee> findAllByOrderByName();

    /** 非分页按企微状态查询（异常筛选路径用） */
    List<Employee> findByWechatStatusOrderByName(Integer wechatStatus);

    /** 按 userid 模糊搜索（关键词搜索用） */
    List<Employee> findByUseridContaining(String userid);
}
