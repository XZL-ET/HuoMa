package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.CustomerOpenidMap;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * external_userid ↔ openid 映射数据访问层。
 *
 * @author Bookstore Dev
 */
public interface CustomerOpenidMapRepository extends JpaRepository<CustomerOpenidMap, Long> {

    Optional<CustomerOpenidMap> findByExternalUserid(String externalUserid);

    Optional<CustomerOpenidMap> findByOpenid(String openid);
}
