package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.CustomerRelation;
import com.bookstore.qrcode.entity.CustomerRelation.RelationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomerRelationRepository extends JpaRepository<CustomerRelation, Long> {

    Optional<CustomerRelation> findByCustomerIdAndEmployeeUserid(Long customerId, String employeeUserid);

    List<CustomerRelation> findByEmployeeUseridAndStatus(String employeeUserid, RelationStatus status);

    List<CustomerRelation> findByCustomerIdAndStatus(Long customerId, RelationStatus status);

    List<CustomerRelation> findByCustomerId(Long customerId);
}
