package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 标签数据访问层。
 * <p>
 * 提供对 tag 表的 CRUD 操作和自定义查询。
 * 标签用于对客户进行分类管理，支持多级层级结构（父子标签），
 * 活码创建时可关联标签以实现客户扫码后自动打标。</p>
 *
 * @author Bookstore Dev Team
 * @since 1.0.0
 */
public interface TagRepository extends JpaRepository<Tag, Long> {

    /**
     * 根据标签类型查询标签列表。
     * <p>
     * 标签按类型分为平台预置标签和商家自定义标签，
     * 此方法用于按类型筛选获取特定类别的标签集合。</p>
     *
     * @param type 标签类型（如平台标签、自定义标签等）
     * @return 该类型下的所有标签列表
     */
    List<Tag> findByType(Tag.TagType type);

    /**
     * 根据父标签 ID 查询直接子标签列表。
     * <p>
     * 标签支持树形层级结构，通过父标签 ID 关联上下级关系。
     * 此方法用于获取某个父标签下的所有直接子标签，
     * 常用于标签管理页面展示层级树。</p>
     *
     * @param parentId 父标签 ID（为 {@code null} 时表示顶级标签）
     * @return 该父标签下的直接子标签列表
     */
    List<Tag> findByParentId(Long parentId);

    /**
     * 根据标签名称精确查询标签。
     * <p>
     * 用于标签去重校验（创建/编辑标签时检查名称是否已存在）
     * 以及按名称快速查找标签。</p>
     *
     * <p>使用 {@code findFirstByName} 而非 {@code findByName}，
     * 防止表中意外存在同名记录时抛出
     * {@link org.springframework.dao.IncorrectResultSizeDataAccessException}。
     * 表上已有 {@code UNIQUE KEY uk_tag_name}，正常情况最多一条。</p>
     *
     * @param name 标签名称
     * @return 匹配的标签对象，未找到时返回 {@link Optional#empty()}
     */
    Optional<Tag> findFirstByName(String name);

    /**
     * 按标签名称和组关键词精确查询（复合唯一键查找）。
     *
     * <p>用于 getOrCreateTag 按 (name, groupKeyword) 定位标签，
     * 支持同名标签在不同企微标签组下独立存在。</p>
     *
     * @param name 标签名称
     * @param groupKeyword 企微标签组关键词
     * @return 匹配的标签对象，未找到时返回 {@link Optional#empty()}
     */
    Optional<Tag> findFirstByNameAndGroupKeyword(String name, String groupKeyword);

    /**
     * 按企微标签 ID + 组关键词定位本地行（canonical 行解析用）。
     *
     * <p>归并必须按 {@code wecom_tag_id} 接线而非按名字：每学期运营会把企微年级标签名
     * 改一级（升学），本地行名与企微当前名可能差一级。</p>
     */
    Optional<Tag> findFirstByWecomTagIdAndGroupKeyword(String wecomTagId, String groupKeyword);

    /**
     * 取所有「名字是年级名、但不属于年级组」的本地 tag 行 —— 即待归并的重复行。
     *
     * <p>用 {@code group_keyword <> '年级'} 而不是 {@code LIKE '学校%'}：历史遗留行
     * 的 group_keyword 可能是空串，但它们同样是重复行。</p>
     */
    @Query("select t from Tag t where t.name in :names and t.groupKeyword <> '年级'")
    List<Tag> findGradeNamedRowsOutsideGradeGroup(@Param("names") Collection<String> names);
}
