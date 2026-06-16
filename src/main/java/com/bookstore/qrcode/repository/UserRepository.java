package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 系统用户数据访问层。
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** 按用户名查找（用于登录认证） */
    Optional<User> findByUsername(String username);

    /** 检查用户名是否已存在 */
    boolean existsByUsername(String username);

    /** 统计启用的用户数量（用于判断是否需要初始化默认管理员） */
    long countByEnabledTrue();
}
