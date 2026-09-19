package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.FormResendTask;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/**
 * 补发表单群发任务数据访问层。
 *
 * @author Bookstore Dev
 */
public interface FormResendTaskRepository extends JpaRepository<FormResendTask, Long> {

    List<FormResendTask> findAllByOrderByCreatedAtDesc();

    List<FormResendTask> findBySender(String sender);
}
