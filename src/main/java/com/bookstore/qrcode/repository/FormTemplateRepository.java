package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.FormTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface FormTemplateRepository extends JpaRepository<FormTemplate, Long> {
    List<FormTemplate> findAllByOrderByName();
}
