package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.CustomerTag;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/**
 * 客户标签关联 数据访问层。
 *
 * <p>提供对 {@link CustomerTag} 表的 CRUD 操作和自定义查询方法。
 * 继承 Spring Data JPA 的 {@link JpaRepository}，自动获得基础增删改查能力。
 * 客户标签关联表（customer_tag）用于维护客户与标签之间的多对多绑定关系，
 * 支持按客户查询所有标签，以及按客户和标签组合进行删除操作。</p>
 *
 * @author Bookstore Dev
 * @since 1.0
 */
public interface CustomerTagRepository extends JpaRepository<CustomerTag, Long> {

    /**
     * 查询指定客户拥有的所有标签关联记录。
     * <p>
     * 用于获取某个客户当前已被打上的全部标签列表，
     * 常用于客户详情页展示标签信息，或扫码后判断是否需要自动打标。
     * </p>
     *
     * @param customerId 客户 ID，关联 customer 表的主键
     * @return 该客户的所有标签关联记录列表
     */
    List<CustomerTag> findByCustomerId(Long customerId);

    /**
     * 删除指定客户与指定标签之间的关联记录。
     * <p>
     * 用于移除客户身上的某个标签。该方法通过数据库层唯一约束
     * （customer_id + tag_id 联合唯一）精确定位到单条记录并删除。
     * 操作实际是物理删除，即从关联表中移除绑定关系，不会影响标签本身。
     * </p>
     *
     * @param customerId 客户 ID
     * @param tagId      标签 ID
     */
    void deleteByCustomerIdAndTagId(Long customerId, Long tagId);

    /**
     * 判断指定客户与指定标签是否已存在关联。
     *
     * @param customerId 客户 ID
     * @param tagId      标签 ID
     * @return true 如果关联已存在
     */
    boolean existsByCustomerIdAndTagId(Long customerId, Long tagId);
}
