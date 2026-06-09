package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Optional<Customer> findByExternalUserid(String externalUserid);
    boolean existsByExternalUserid(String externalUserid);

    @Query("SELECT c FROM Customer c WHERE "
         + "(:keyword IS NULL OR c.name LIKE %:keyword% OR c.externalUserid LIKE %:keyword%) "
         + "AND (:schoolId IS NULL OR c.schoolId = :schoolId) "
         + "AND (:currentAgent IS NULL OR c.currentAgent = :currentAgent) "
         + "AND (:status IS NULL OR c.status = :status) "
         + "AND (:startTime IS NULL OR c.addTime >= :startTime) "
         + "AND (:endTime IS NULL OR c.addTime <= :endTime)")
    Page<Customer> search(@Param("keyword") String keyword,
                          @Param("schoolId") String schoolId,
                          @Param("currentAgent") String currentAgent,
                          @Param("status") Customer.CustomerStatus status,
                          @Param("startTime") LocalDateTime startTime,
                          @Param("endTime") LocalDateTime endTime,
                          Pageable pageable);

    long countByAddTimeBetween(LocalDateTime start, LocalDateTime end);
    long countByAddTimeBetweenAndStatus(LocalDateTime start, LocalDateTime end,
                                        Customer.CustomerStatus status);
    long countBySourceQrIdAndAddTimeBetween(Long sourceQrId, LocalDateTime start, LocalDateTime end);
}
