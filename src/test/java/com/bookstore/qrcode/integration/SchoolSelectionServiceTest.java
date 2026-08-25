package com.bookstore.qrcode.integration;

import com.bookstore.qrcode.config.RedisConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bookstore.qrcode.entity.*;
import com.bookstore.qrcode.repository.*;
import com.bookstore.qrcode.service.SchoolSelectionService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

@Import(WecomApiMockConfig.class)
@DisplayName("县区选校服务测试")
class SchoolSelectionServiceTest extends BaseIntegrationTest {

    @Autowired private SchoolSelectionService service;
    @Autowired private SchoolRepository schoolRepo;
    @Autowired private SchoolCategoryRepository categoryRepo;
    @Autowired private QrCodeRepository qrCodeRepo;
    @Autowired private QrCodeGroupRepository groupRepo;
    @Autowired private QrAgentRepository qrAgentRepo;
    @Autowired private AgentRepository agentRepo;
    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private StringRedisTemplate redisTemplate;

    private QrCode countyQr;

    @BeforeEach
    void setUp() {
        schoolRepo.deleteAll();
        groupRepo.deleteAll();
        qrAgentRepo.deleteAll();
        agentRepo.deleteAll();
        qrCodeRepo.deleteAll();

        countyQr = new QrCode();
        countyQr.setSchoolName("白银区");
        countyQr.setSchoolId("county:白银区");
        countyQr.setRegionCity("白银市");
        countyQr.setRegionDistrict("白银区");
        countyQr.setStatus(QrCode.QrCodeStatus.active);
        countyQr.setScene(Scene.daily_push);
        countyQr.setCreateMode(QrCode.CreateMode.manual);
        countyQr = qrCodeRepo.save(countyQr);
    }

    @Test
    void 检测县区码() {
        assertThat(service.isCountyCode(countyQr)).isTrue();
        QrCode schoolQr = new QrCode();
        schoolQr.setSchoolName("白银一小");
        schoolQr.setSchoolId("s1");
        schoolQr.setRegionCity("白银市");
        schoolQr.setRegionDistrict("白银区");
        schoolQr = qrCodeRepo.save(schoolQr);
        assertThat(service.isCountyCode(schoolQr)).isFalse();
    }

    @Test
    void 按学段列出县区学校() {
        SchoolCategory primary = categoryRepo.save(SchoolCategory.builder()
            .name("小学").sortOrder(1).build());
        schoolRepo.save(School.builder().schoolId("s1").schoolName("白银一小")
            .regionCity("白银市").regionDistrict("白银区").categoryId(primary.getId()).deleted(false).build());
        schoolRepo.save(School.builder().schoolId("s2").schoolName("白银一中")
            .regionCity("白银市").regionDistrict("白银区").categoryId(null).deleted(false).build());

        List<SchoolSelectionService.SchoolOption> primarySchools =
            service.listSchools(countyQr.getId(), "小学");
        assertThat(primarySchools).extracting(SchoolSelectionService.SchoolOption::schoolName)
            .containsExactly("白银一小");

        List<SchoolSelectionService.SchoolOption> all = service.listSchools(countyQr.getId(), "全部");
        assertThat(all).hasSize(2);
    }

    @Test
    void 按学段返回年级枚举() {
        assertThat(service.listGrades("小学"))
            .containsExactly("一年级", "二年级", "三年级", "四年级", "五年级", "六年级");
        assertThat(service.listGrades("幼儿园")).containsExactly("小班", "中班", "大班");
        assertThat(service.listGrades("不存在的学段")).isEmpty();
    }

    private QrCode buildSchoolQr(String schoolId, String schoolName, String serviceUserid) {
        QrCode qr = new QrCode();
        qr.setSchoolName(schoolName);
        qr.setSchoolId(schoolId);
        qr.setRegionCity("白银市");
        qr.setRegionDistrict("白银区");
        qr.setStatus(QrCode.QrCodeStatus.active);
        qr.setScene(Scene.daily_push);
        qr.setCreateMode(QrCode.CreateMode.manual);
        qr = qrCodeRepo.save(qr);
        if (serviceUserid != null) {
            agentRepo.save(Agent.builder().userid(serviceUserid).name(serviceUserid)
                .role(Agent.AgentRole.service).dailyTotalCap(500).build());
            qrAgentRepo.save(QrAgent.builder().qrCodeId(qr.getId())
                .agentUserid(serviceUserid).role(QrAgent.AgentRole.service)
                .status(QrAgent.AgentStatus.active).build());
        }
        return qr;
    }

    @Test
    void 解析链_一校一码() {
        buildSchoolQr("s1", "白银一小", "svc_a");
        Optional<SchoolSelectionService.TransferTarget> target =
            service.resolveTransferTarget("s1", "白银一小");
        assertThat(target).isPresent();
        assertThat(target.get().state()).isEqualTo("s1");
        assertThat(target.get().toUserid()).isEqualTo("svc_a");
    }

    @Test
    void 解析链_学区码兜底() {
        // 学校无独立活码，但包在学区码 schoolList 里
        QrCode allianceQr = buildSchoolQr("alliance:白银区", "白银区联盟", "svc_district");
        groupRepo.save(QrCodeGroup.builder().name("白银联盟").regionCity("白银市")
            .regionDistrict("白银区").groupType("alliance")
            .qrCodeId(allianceQr.getId())
            .schoolList("白银一小\n白银二小").build());

        Optional<SchoolSelectionService.TransferTarget> target =
            service.resolveTransferTarget("s9", "白银二小");
        assertThat(target).isPresent();
        assertThat(target.get().state()).isEqualTo("alliance:白银区");
        assertThat(target.get().toUserid()).isEqualTo("svc_district");
    }

    @Test
    void 解析链_既无独立活码也不在学区码() {
        assertThat(service.resolveTransferTarget("s99", "幽灵学校")).isEmpty();
    }

    @Test
    void 发起县区转接_写入转接流() throws Exception {
        buildSchoolQr("s1", "白银一小", "svc_a");
        boolean published = service.initiateCountyTransfer(
            123L, "rec_county", "wm-external", "s1", "白银一小");
        assertThat(published).isTrue();

        var records = redisTemplate.opsForStream()
            .range(RedisConfig.TRANSFER_STREAM_KEY,
                org.springframework.data.domain.Range.unbounded());
        assertThat(records).hasSize(1);

        String eventJson = (String) records.get(0).getValue().get("event");
        assertThat(eventJson).isNotBlank();
        JsonNode event = new ObjectMapper().readTree(eventJson);
        assertThat(event.get("customer_id").asText()).isEqualTo("123");
        assertThat(event.get("from_userid").asText()).isEqualTo("rec_county");
        assertThat(event.get("to_userid").asText()).isEqualTo("svc_a");
        assertThat(event.get("external_userid").asText()).isEqualTo("wm-external");
        assertThat(event.get("state").asText()).isEqualTo("s1");
    }
}
