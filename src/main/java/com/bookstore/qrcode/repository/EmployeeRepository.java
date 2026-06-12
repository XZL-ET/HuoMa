package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

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
}
