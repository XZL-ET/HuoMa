package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.CustomerTag;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CustomerTagRepository extends JpaRepository<CustomerTag, Long> {
    List<CustomerTag> findByCustomerId(Long customerId);
    void deleteByCustomerIdAndTagId(Long customerId, Long tagId);
}
