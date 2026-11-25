package com.bookstore.qrcode.service;

import com.bookstore.qrcode.entity.Agent;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.GlobalAgentPool;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.GlobalAgentPoolRepository;
import com.bookstore.qrcode.repository.PoolAllowedDepartmentRepository;
import com.bookstore.qrcode.wecom.WecomApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 严格同部门取人回归：活码取人只在本县（含子孙部门）内选，绝不跨县全局降级。
 *
 * <p>用真实 H2 + 真实 ID 服务，仅 mock 企微 API 与部门树服务，
 * 以便同时覆盖「取人主链路」与「部门树失败降级」两类语义。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("GlobalAgentPoolService 严格同部门取人")
class GlobalAgentPoolServiceStrictTest {

    /** 本县（口径根部门）。 */
    private static final Long LOCAL_DEPT = 66L;

    /** 外县。 */
    private static final Long OTHER_DEPT = 54L;

    /** 本测试独占的 userid，避免与其它用例的池/员工数据互相污染。 */
    private static final List<String> TEST_USERIDS =
        List.of("strictA", "strictB", "strictC", "strictD");

    @Autowired private GlobalAgentPoolService poolService;
    @Autowired private StrictDeptService strictDeptService;
    @Autowired private StrictDeptConfigService strictDeptConfig;
    @Autowired private GlobalAgentPoolRepository poolRepo;
    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private AgentRepository agentRepo;
    @Autowired private PoolAllowedDepartmentRepository whitelistRepo;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private WecomApiClient wecomApi;
    @MockBean private DepartmentService departmentService;

    @BeforeEach
    void setUp() throws Exception {
        // R8：每个用例显式设置开关，不依赖默认值
        strictDeptConfig.setStrictModeEnabled(false);
        // 白名单必须为空，否则真实白名单会先于「本县」判定把候选过滤掉
        whitelistRepo.deleteAll();
        cleanTestRows();
        // 默认部门树：按 parentId 查询时无子部门
        when(wecomApi.listDepartments(any()))
            .thenReturn(objectMapper.readTree("{\"department\":[]}"));
    }

    @AfterEach
    void tearDown() {
        strictDeptConfig.setStrictModeEnabled(false);
        cleanTestRows();
    }

    @Test
    @DisplayName("严格模式只取本县且不跨县降级")
    void strictModeTakesLocalOnlyWithoutCrossCountyFallback() {
        strictDeptConfig.setStrictModeEnabled(true);
        seedMember("strictA", "[" + LOCAL_DEPT + "]", LOCAL_DEPT, 1);
        seedMember("strictB", "[" + OTHER_DEPT + "]", OTHER_DEPT, 2);

        GlobalAgentPool first = poolService.takeStandby(Set.of(), LOCAL_DEPT);
        assertThat(first).isNotNull();
        assertThat(first.getAgentUserid()).isEqualTo("strictA");

        // 删掉本县唯一的 A，B 仍在池中 —— 严格模式不得跨县取 B
        poolRepo.delete(poolRepo.findByAgentUserid("strictA").orElseThrow());

        GlobalAgentPool second = poolService.takeStandby(Set.of(), LOCAL_DEPT);
        assertThat(second).as("本县无人时严格模式必须返回 null").isNull();
        assertThat(poolRepo.findByAgentUserid("strictB")).isPresent();
    }

    @Test
    @DisplayName("多部门员工次部门命中即可选中")
    void multiDeptEmployeeMatchesBySecondaryDepartment() {
        strictDeptConfig.setStrictModeEnabled(true);
        // 主部门 54（外县），次部门 66（本县）；池里的 department_id 记录的是主部门
        seedMember("strictC", "[" + OTHER_DEPT + "," + LOCAL_DEPT + "]", OTHER_DEPT, 1);

        GlobalAgentPool taken = poolService.takeStandby(Set.of(), LOCAL_DEPT);

        assertThat(taken).isNotNull();
        assertThat(taken.getAgentUserid()).isEqualTo("strictC");
        assertThat(taken.getDepartmentId()).isEqualTo(OTHER_DEPT);
    }

    @Test
    @DisplayName("严格模式缺部门返回空")
    void strictModeWithoutDepartmentReturnsNull() {
        strictDeptConfig.setStrictModeEnabled(true);
        seedMember("strictA", "[" + LOCAL_DEPT + "]", LOCAL_DEPT, 1);

        assertThat(poolService.takeStandby(Set.of(), null)).isNull();
    }

    @Test
    @DisplayName("关闭严格模式恢复全局降级")
    void nonStrictModeFallsBackToGlobal() {
        strictDeptConfig.setStrictModeEnabled(false);
        Long emptyLocalRoot = 78L;
        seedMember("strictB", "[" + OTHER_DEPT + "]", OTHER_DEPT, 1);

        GlobalAgentPool taken = poolService.takeStandby(Set.of(), emptyLocalRoot);

        assertThat(taken).isNotNull();
        assertThat(taken.getAgentUserid()).isEqualTo("strictB");
    }

    @Test
    @DisplayName("部门树拉取失败不缓存降级结果")
    void failedDeptTreeFetchIsNotCached() throws Exception {
        strictDeptConfig.setStrictModeEnabled(true);
        Long localRoot = 90L;
        Long subDept = 91L;
        seedMember("strictD", "[" + subDept + "]", subDept, 1);

        // 首次：拉取部门树失败 → 本次只按精确 root 判定，子部门 91 不命中
        // 用 doThrow/doReturn 重设 stub：普通 when() 在已配置抛异常时会先真的抛出
        doThrow(new RuntimeException("wecom down")).when(wecomApi).listDepartments(localRoot);

        GlobalAgentPool first = poolService.takeStandbyStrict(Set.of(), localRoot);
        assertThat(first).as("树不可用时不得命中子部门员工").isNull();

        // 再次：拉取成功且含子部门 → 子部门员工可命中（失败结果不能被缓存毒化）
        JsonNode childTree = objectMapper.readTree(
            "{\"department\":[{\"id\":" + subDept + ",\"name\":\"子部门\",\"parentid\":"
                + localRoot + "}]}");
        doReturn(childTree).when(wecomApi).listDepartments(localRoot);

        GlobalAgentPool second = poolService.takeStandbyStrict(Set.of(), localRoot);
        assertThat(second).as("失败结果未缓存，第二次成功拉取后应命中子部门员工").isNotNull();
        assertThat(second.getAgentUserid()).isEqualTo("strictD");

        verify(wecomApi, times(2)).listDepartments(localRoot);
    }

    @Test
    @DisplayName("isLocal 部门树不可用退回精确 ID")
    void isLocalFallsBackToExactIdWhenTreeUnavailable() {
        when(departmentService.loadDeptTree())
            .thenThrow(new RuntimeException("dept tree down"));

        Employee local = Employee.builder()
            .userid("strictLocal").name("本县").department("[" + LOCAL_DEPT + "]").build();
        Employee other = Employee.builder()
            .userid("strictOther").name("外县").department("[" + OTHER_DEPT + "]").build();

        assertThat(strictDeptService.isLocal(local, LOCAL_DEPT)).isTrue();
        assertThat(strictDeptService.isLocal(other, LOCAL_DEPT)).isFalse();
    }

    // ── helpers ──

    private void seedMember(String userid, String departmentJson, Long poolDeptId, int sortOrder) {
        agentRepo.save(Agent.builder().userid(userid).name(userid).build());
        employeeRepo.save(Employee.builder()
            .userid(userid).name(userid).department(departmentJson)
            .active(true).wechatStatus(1).build());
        poolRepo.save(GlobalAgentPool.builder()
            .agentUserid(userid).departmentId(poolDeptId)
            .sortOrder(sortOrder).dailyMax(150)
            .status(GlobalAgentPool.PoolStatus.standby).build());
    }

    private void cleanTestRows() {
        for (String userid : TEST_USERIDS) {
            poolRepo.findByAgentUserid(userid).ifPresent(poolRepo::delete);
            employeeRepo.findByUserid(userid).ifPresent(employeeRepo::delete);
            agentRepo.findById(userid).ifPresent(agentRepo::delete);
        }
    }
}
