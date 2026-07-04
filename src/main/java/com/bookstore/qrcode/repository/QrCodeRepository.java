package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.dto.QrCodeTreeDto;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.entity.Scene;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 活码（QrCode）数据访问层。
 * <p>
 * 提供对 {@link QrCode} 实体的基础 CRUD 操作以及按学校、地域、状态等多维度的
 * 组合搜索能力。活码即"客户二维码"，每个活码关联一所学校，客户扫码后进入加好友流程。
 * </p>
 *
 * @author Bookstore Dev Team
 * @since 1.0
 */
public interface QrCodeRepository extends JpaRepository<QrCode, Long> {

    /**
     * 根据学校 ID 查找活码。
     * <p>
     * 学校 ID 在企业微信通讯录中唯一标识一所学校（对应一个校区），
     * 每所学校在同一时间至多只能有一个生效的活码。
     * </p>
     *
     * @param schoolId 学校企业微信 ID，不可为 null
     * @return 包含匹配活码的 Optional；若未找到则返回 {@link Optional#empty()}
     */
    Optional<QrCode> findBySchoolId(String schoolId);

    /**
     * 判断指定学校 ID 是否已存在活码记录。
     * <p>
     * 通常用于创建新活码前的校验，防止同一学校重复创建。
     * </p>
     *
     * @param schoolId 学校企业微信 ID，不可为 null
     * @return 已存在返回 {@code true}，否则返回 {@code false}
     */
    boolean existsBySchoolId(String schoolId);

    /** 判断指定学校名称是否已存在活码（用于批量导入去重） */
    boolean existsBySchoolName(String schoolName);

    /** 判断同城市下是否已有同名学校活码（用于手动创建去重预警） */
    boolean existsBySchoolNameAndRegionCity(String schoolName, String regionCity);

    /**
     * 多条件组合分页搜索活码。
     * <p>
     * JPQL 说明：所有筛选条件均使用 {@code :param IS NULL OR ...} 模式，
     * 当参数为 {@code null} 时自动忽略该条件。这使得前端可以任意组合筛选维度
     * 而无需为每种组合编写独立的查询方法。
     * <ul>
     *   <li><b>keyword</b>（可选）：模糊匹配学校名称（schoolName）、学校 ID（schoolId）、
     *       城市（regionCity）、区县（regionDistrict），
     *       使用 {@code LIKE %:keyword%} 实现前后模糊</li>
     *   <li><b>city</b>（可选）：精确匹配所在城市（regionCity）</li>
     *   <li><b>district</b>（可选）：精确匹配所在区/县（regionDistrict）</li>
     *   <li><b>status</b>（可选）：精确匹配活码状态（status），
     *       如 {@link QrCode.QrCodeStatus#ACTIVE} 或 {@link QrCode.QrCodeStatus#DISABLED}</li>
     *   <li><b>groupId</b>（可选）：精确匹配所属群组 ID（groupId），
     *       为 {@code null} 时忽略此筛选</li>
     * </ul>
     * </p>
     *
     * @param keyword  搜索关键字（学校名称/学校 ID 模糊匹配），可为 {@code null}
     * @param city     城市筛选，可为 {@code null}
     * @param district 区/县筛选，可为 {@code null}
     * @param status   活码状态筛选，可为 {@code null}
     * @param groupId  群组 ID 筛选，可为 {@code null}
     * @param pageable 分页参数（页码、每页条数、排序等）
     * @return 满足条件的活码分页数据
     */
    @Query("SELECT q FROM QrCode q WHERE "
         + "(:keyword IS NULL OR q.schoolName LIKE %:keyword% OR q.schoolId LIKE %:keyword%"
         + " OR q.regionCity LIKE %:keyword% OR q.regionDistrict LIKE %:keyword%) "
         + "AND (:city IS NULL OR q.regionCity = :city) "
         + "AND (:district IS NULL OR q.regionDistrict = :district) "
         + "AND (:status IS NULL OR q.status = :status) "
         + "AND (:groupId IS NULL OR q.groupId = :groupId) "
         + "ORDER BY q.createdAt DESC")
    Page<QrCode> search(@Param("keyword") String keyword,
                        @Param("city") String city,
                        @Param("district") String district,
                        @Param("status") QrCode.QrCodeStatus status,
                        @Param("groupId") Long groupId,
                        Pageable pageable);

    /**
     * 统计指定状态的活码数量。
     * <p>
     * 用于管理后台 Dashboard 展示各类状态的活码总数。
     * </p>
     *
     * @param status 活码状态（ACTIVE / DISABLED 等）
     * @return 该状态下的活码数量
     */
    long countByStatus(QrCode.QrCodeStatus status);

    /**
     * 根据状态查询活码列表。
     *
     * @param status 活码状态
     * @return 该状态下的所有活码
     */
    List<QrCode> findByStatus(QrCode.QrCodeStatus status);

    /**
     * 查询全部活码列表。
     *
     * @return 数据库中所有活码
     */
    List<QrCode> findAll();

    /**
     * 根据分组 ID 查询活码列表，按校名字母序排序。
     *
     * @param groupId 分组主键 ID
     * @return 该分组下的活码列表，按校名字母序升序排列
     */
    List<QrCode> findByGroupIdOrderBySchoolName(Long groupId);

    /**
     * 获取所有已使用的城市列表（去重、排序）。
     * <p>
     * JPQL 使用 {@code SELECT DISTINCT} 去重，并按城市名称字母序排列。
     * 用于前端地域筛选下拉框的数据源。
     * </p>
     *
     * @return 排重后的城市名称列表，按字母序升序排列
     */
    @Query("SELECT DISTINCT q.regionCity FROM QrCode q WHERE q.regionCity IS NOT NULL ORDER BY q.regionCity")
    List<String> findDistinctRegionCity();

    /**
     * 获取所有已使用的区/县列表（去重、排序）。
     * <p>
     * JPQL 使用 {@code SELECT DISTINCT} 去重，并按区/县名称字母序排列。
     * 用于前端地域筛选的下级下拉框数据源。
     * </p>
     *
     * @return 排重后的区/县名称列表，按字母序升序排列
     */
    @Query("SELECT DISTINCT q.regionDistrict FROM QrCode q WHERE q.regionDistrict IS NOT NULL ORDER BY q.regionDistrict")
    List<String> findDistinctRegionDistrict();

    /**
     * 获取所有已使用的学校名称列表（去重、排序）。
     *
     * @return 排重后的学校名称列表，按字母序升序排列
     */
    @Query("SELECT DISTINCT q.schoolName FROM QrCode q WHERE q.schoolName IS NOT NULL ORDER BY q.schoolName")
    List<String> findDistinctSchoolName();

    /**
     * 查询孤儿活码候选 — 状态异常（paused / no_agent）但仍有企微 config_id 的 QR 码。
     * <p>
     * 用于 {@code PatrolWorker.reconcileOrphanQrCodes()} 企微对账扫描，
     * 逐条验证企微侧是否仍存在该活码配置。
     * </p>
     *
     * @return 符合条件的孤儿 QR 码列表
     */
    @Query("SELECT q FROM QrCode q WHERE q.status IN ('paused', 'no_agent') "
         + "AND q.qrConfigId IS NOT NULL AND q.qrConfigId <> ''")
    List<QrCode> findOrphanCandidates();

    /** 查询活码的第一个 active 服务老师姓名 */
    @Query(value = "SELECT a.name FROM qr_agent qa " +
           "JOIN agent a ON a.userid = qa.agent_userid " +
           "WHERE qa.qr_code_id = :qrCodeId AND qa.role IN ('service', 'dual') " +
           "AND qa.status = 'active' LIMIT 1", nativeQuery = true)
    String findFirstServiceAgentName(@Param("qrCodeId") Long qrCodeId);

    /**
     * 分页搜索联盟活码 — id 在 QrCodeGroup 表中有记录。
     * <p>
     * 与 {@link #search(String, String, String, QrCode.QrCodeStatus, Long, Pageable)} 相同的
     * {@code :param IS NULL OR ...} 筛选模式，额外增加子查询限制仅返回联盟范围内的活码。
     * </p>
     *
     * @param keyword  搜索关键字，可为 {@code null}
     * @param city     城市筛选，可为 {@code null}
     * @param district 区/县筛选，可为 {@code null}
     * @param status   活码状态筛选，可为 {@code null}
     * @param groupId  群组 ID 筛选，可为 {@code null}
     * @param pageable 分页参数
     * @return 联盟范围内的活码分页数据
     */
    @Query("SELECT q FROM QrCode q WHERE "
         + "(:keyword IS NULL OR q.schoolName LIKE %:keyword% OR q.schoolId LIKE %:keyword%"
         + " OR q.regionCity LIKE %:keyword% OR q.regionDistrict LIKE %:keyword%) "
         + "AND (:city IS NULL OR q.regionCity = :city) "
         + "AND (:district IS NULL OR q.regionDistrict = :district) "
         + "AND (:status IS NULL OR q.status = :status) "
         + "AND (:groupId IS NULL OR q.groupId = :groupId) "
         + "AND q.id IN (SELECT g.qrCodeId FROM QrCodeGroup g WHERE g.qrCodeId IS NOT NULL) "
         + "ORDER BY q.createdAt DESC")
    Page<QrCode> searchAlliance(@Param("keyword") String keyword,
                                 @Param("city") String city,
                                 @Param("district") String district,
                                 @Param("status") QrCode.QrCodeStatus status,
                                 @Param("groupId") Long groupId,
                                 Pageable pageable);

    /**
     * 分页搜索非联盟活码 — id 不在 QrCodeGroup 表中有记录。
     * <p>
     * 与 {@link #searchAlliance(String, String, String, QrCode.QrCodeStatus, Long, Pageable)}
     * 互为补集，使用 {@code NOT IN} 子查询排除联盟活码。
     * </p>
     *
     * @param keyword  搜索关键字，可为 {@code null}
     * @param city     城市筛选，可为 {@code null}
     * @param district 区/县筛选，可为 {@code null}
     * @param status   活码状态筛选，可为 {@code null}
     * @param groupId  群组 ID 筛选，可为 {@code null}
     * @param pageable 分页参数
     * @return 非联盟范围内的活码分页数据
     */
    @Query("SELECT q FROM QrCode q WHERE "
         + "(:keyword IS NULL OR q.schoolName LIKE %:keyword% OR q.schoolId LIKE %:keyword%"
         + " OR q.regionCity LIKE %:keyword% OR q.regionDistrict LIKE %:keyword%) "
         + "AND (:city IS NULL OR q.regionCity = :city) "
         + "AND (:district IS NULL OR q.regionDistrict = :district) "
         + "AND (:status IS NULL OR q.status = :status) "
         + "AND (:groupId IS NULL OR q.groupId = :groupId) "
         + "AND q.id NOT IN (SELECT g.qrCodeId FROM QrCodeGroup g WHERE g.qrCodeId IS NOT NULL) "
         + "ORDER BY q.createdAt DESC")
    Page<QrCode> searchSchool(@Param("keyword") String keyword,
                               @Param("city") String city,
                               @Param("district") String district,
                               @Param("status") QrCode.QrCodeStatus status,
                               @Param("groupId") Long groupId,
                               Pageable pageable);

    /**
     * 不分页查询活码列表（用于导出 Excel）。
     * <p>
     * 当 {@code allianceOnly} 为 {@code true} 时仅含联盟活码，
     * 为 {@code false} 时仅含非联盟活码，
     * 为 {@code null} 时返回全部活码。
     * </p>
     *
     * @param keyword      搜索关键字，可为 {@code null}
     * @param city         城市筛选，可为 {@code null}
     * @param district     区/县筛选，可为 {@code null}
     * @param status       活码状态筛选，可为 {@code null}
     * @param groupId      群组 ID 筛选，可为 {@code null}
     * @param allianceOnly 联盟范围控制：{@code true}=联盟，{@code false}=非联盟，{@code null}=全部
     * @return 满足条件的活码列表（无分页）
     */
    @Query("SELECT q FROM QrCode q WHERE "
         + "(:keyword IS NULL OR q.schoolName LIKE %:keyword% OR q.schoolId LIKE %:keyword%"
         + " OR q.regionCity LIKE %:keyword% OR q.regionDistrict LIKE %:keyword%) "
         + "AND (:city IS NULL OR q.regionCity = :city) "
         + "AND (:district IS NULL OR q.regionDistrict = :district) "
         + "AND (:status IS NULL OR q.status = :status) "
         + "AND (:groupId IS NULL OR q.groupId = :groupId) "
         + "AND (:allianceOnly IS NULL OR "
         + "  (:allianceOnly = true AND q.id IN (SELECT g.qrCodeId FROM QrCodeGroup g WHERE g.qrCodeId IS NOT NULL)) OR "
         + "  (:allianceOnly = false AND q.id NOT IN (SELECT g.qrCodeId FROM QrCodeGroup g WHERE g.qrCodeId IS NOT NULL))) "
         + "ORDER BY q.createdAt DESC")
    List<QrCode> findAllForExport(@Param("keyword") String keyword,
                                   @Param("city") String city,
                                   @Param("district") String district,
                                   @Param("status") QrCode.QrCodeStatus status,
                                   @Param("groupId") Long groupId,
                                   @Param("allianceOnly") Boolean allianceOnly);

    /**
     * 活码树节点投影查询 — 仅加载树渲染所需的 5 个字段，
     * 替代 {@code findAll()} 加载全部 25 列的浪费。
     *
     * @return 活码树 DTO 列表（仅含 id、schoolName、regionCity、regionDistrict、groupId）
     */
    @Query("SELECT new com.bookstore.qrcode.dto.QrCodeTreeDto(" +
           "q.id, q.schoolName, q.schoolId, q.regionCity, q.regionDistrict, q.groupId) " +
           "FROM QrCode q")
    List<QrCodeTreeDto> findAllTreeProjection();

    /**
     * 查询未关联任何联盟的活码投影（用于新建联盟时选择活码）。
     */
    @Query("SELECT new com.bookstore.qrcode.dto.QrCodeTreeDto(" +
           "q.id, q.schoolName, q.schoolId, q.regionCity, q.regionDistrict, q.groupId) " +
           "FROM QrCode q WHERE q.id NOT IN " +
           "(SELECT g.qrCodeId FROM QrCodeGroup g WHERE g.qrCodeId IS NOT NULL) " +
           "ORDER BY q.schoolName")
    List<QrCodeTreeDto> findUnassignedTreeProjection();

    // ── 批量更新方法 ──

    /** 批量更新欢迎语 */
    @Modifying
    @Query("UPDATE QrCode q SET q.welcomeText = :welcomeText WHERE q.id IN :ids")
    int batchUpdateWelcomeText(@Param("welcomeText") String welcomeText,
                                @Param("ids") List<Long> ids);

    /** 批量更新表单模板（null 表示清空） */
    @Modifying
    @Query("UPDATE QrCode q SET q.formTemplateId = :formTemplateId WHERE q.id IN :ids")
    int batchUpdateFormTemplateId(@Param("formTemplateId") Long formTemplateId,
                                   @Param("ids") List<Long> ids);

    /** 统计引用了指定表单模板的活码数（删除保护） */
    long countByFormTemplateId(Long formTemplateId);

    /** 批量切换轮换模式 */
    @Modifying
    @Query("UPDATE QrCode q SET q.rotateMode = :mode WHERE q.id IN :ids")
    int batchUpdateRotateMode(@Param("mode") QrCode.RotateMode mode,
                               @Param("ids") List<Long> ids);

    /** 批量改分组（null 表示取消分组） */
    @Modifying
    @Query("UPDATE QrCode q SET q.groupId = :groupId WHERE q.id IN :ids")
    int batchUpdateGroupId(@Param("groupId") Long groupId,
                            @Param("ids") List<Long> ids);

    /** 批量更新告警/紧急阈值 */
    @Modifying
    @Query("UPDATE QrCode q SET q.warnRatio = :warnRatio, q.urgentRatio = :urgentRatio "
         + "WHERE q.id IN :ids")
    int batchUpdateThresholds(@Param("warnRatio") int warnRatio,
                               @Param("urgentRatio") int urgentRatio,
                               @Param("ids") List<Long> ids);

    /** 批量更新状态（暂停/启用） */
    @Modifying
    @Query("UPDATE QrCode q SET q.status = :status WHERE q.id IN :ids")
    int batchUpdateStatus(@Param("status") QrCode.QrCodeStatus status,
                           @Param("ids") List<Long> ids);

    /** 批量切换场景，同时更新紧急阈值以匹配场景预设 */
    @Modifying
    @Query("UPDATE QrCode q SET q.scene = :scene, q.urgentRatio = :urgentRatio WHERE q.id IN :ids")
    int batchUpdateScene(@Param("scene") Scene scene,
                         @Param("urgentRatio") int urgentRatio,
                         @Param("ids") List<Long> ids);
}
