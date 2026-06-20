package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.FormSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface FormSubmissionRepository extends JpaRepository<FormSubmission, Long> {
    List<FormSubmission> findByCustomerIdOrderBySubmittedAtDesc(Long customerId);
    boolean existsByCustomerId(Long customerId);
}
