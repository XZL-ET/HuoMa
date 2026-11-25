package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.dto.DeletionGroupRow;
import com.bookstore.qrcode.entity.Customer;
import com.bookstore.qrcode.entity.CustomerDeletionEvent;
import com.bookstore.qrcode.entity.Employee;
import com.bookstore.qrcode.entity.QrCode;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.CustomerDeletionEventRepository;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.DeletionReportService;
import com.bookstore.qrcode.service.DepartmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeletionRecordController 删除记录列表")
class DeletionRecordControllerTest {

    @Mock private CustomerDeletionEventRepository deletionRepo;
    @Mock private CustomerRepository customerRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private AgentRepository agentRepo;
    @Mock private QrCodeRepository qrCodeRepo;
    @Mock private DeletionReportService deletionReportService;
    @Mock private DepartmentService departmentService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private DeletionRecordController controller;

    private CustomerDeletionEvent event(String externalUserid, String userid,
                                        CustomerDeletionEvent.Direction direction,
                                        LocalDateTime deletedAt) {
        return CustomerDeletionEvent.builder()
                .externalUserid(externalUserid)
                .userid(userid)
                .direction(direction)
                .deletedAt(deletedAt)
                .build();
    }

    @Test
    @DisplayName("yesterday 预设：起止都落在昨日")
    void yesterdayRangeSetsWindowToYesterday() {
        when(deletionRepo.findByDeletedAtBetweenOrderByDeletedAtDesc(any(), any()))
                .thenReturn(List.of());

        ExtendedModelMap model = new ExtendedModelMap();
        controller.list("yesterday", null, null, "CUSTOMER_DELETED_AGENT", null, model);

        LocalDate yesterday = LocalDate.now().minusDays(1);
        assertThat(model.getAttribute("start")).isEqualTo(yesterday.toString());
        assertThat(model.getAttribute("end")).isEqualTo(yesterday.toString());
    }

    @Test
    @DisplayName("direction 过滤：只保留客户删除员工")
    void filtersByDirection() {
        LocalDateTime now = LocalDateTime.now();
        when(deletionRepo.findByDeletedAtBetweenOrderByDeletedAtDesc(any(), any()))
                .thenReturn(List.of(
                        event("wm-c1", "agent1", CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, now),
                        event("wm-c2", "agent2", CustomerDeletionEvent.Direction.AGENT_DELETED_CUSTOMER, now)));

        ExtendedModelMap model = new ExtendedModelMap();
        controller.list("7d", null, null, "CUSTOMER_DELETED_AGENT", null, model);

        @SuppressWarnings("unchecked")
        List<DeletionGroupRow> groups = (List<DeletionGroupRow>) model.getAttribute("groups");
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).direction()).isEqualTo(CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT);
    }

    @Test
    @DisplayName("list — 同一客户多次删除同一员工聚合为一行并保留次数与首末时间")
    void aggregatesRepeatedDeletionsIntoOneRow() {
        LocalDateTime older = LocalDateTime.now().minusHours(2);
        LocalDateTime newer = LocalDateTime.now().minusMinutes(5);
        when(deletionRepo.findByDeletedAtBetweenOrderByDeletedAtDesc(any(), any()))
                .thenReturn(List.of(
                        event("wm-c1", "agent1", CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, newer),
                        event("wm-c1", "agent1", CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, older),
                        event("wm-c2", "agent1", CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, older)));

        ExtendedModelMap model = new ExtendedModelMap();
        controller.list("7d", null, null, "CUSTOMER_DELETED_AGENT", null, model);

        @SuppressWarnings("unchecked")
        List<DeletionGroupRow> groups = (List<DeletionGroupRow>) model.getAttribute("groups");
        assertThat(groups).hasSize(2);
        DeletionGroupRow repeated = groups.get(0);
        assertThat(repeated.externalUserid()).isEqualTo("wm-c1");
        assertThat(repeated.count()).isEqualTo(2);
        assertThat(repeated.firstDeletedAt()).isEqualTo(older);
        assertThat(repeated.lastDeletedAt()).isEqualTo(newer);
        assertThat(repeated.times()).containsExactly(newer, older);
        assertThat(model.getAttribute("totalGroups")).isEqualTo(2);
        assertThat(model.getAttribute("totalEvents")).isEqualTo(3);
    }

    @Test
    @DisplayName("export — 用全量查询导出 xlsx，含表头与方向中文")
    void exportUsesUnboundedQueryAndWritesExcel() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        CustomerDeletionEvent evt = event("wm-c1", "agent1",
                CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, now);
        when(deletionRepo.findByDeletedAtBetweenOrderByDeletedAtDesc(any(), any()))
                .thenReturn(List.of(evt));

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.export("7d", null, null, "CUSTOMER_DELETED_AGENT", null, response);

        assertThat(response.getContentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(response.getHeader("Content-Disposition"))
                .contains("attachment")
                .contains(".xlsx");

        byte[] bytes = response.getContentAsByteArray();
        assertThat(bytes).isNotEmpty();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("客户");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("wm-c1");
            assertThat(sheet.getRow(1).getCell(4).getStringCellValue()).isEqualTo("客户删除员工");
        }
    }

    @Test
    @DisplayName("export — 遵循方向过滤：员工删客户显示对应中文")
    void exportAppliesDirectionFilter() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        when(deletionRepo.findByDeletedAtBetweenOrderByDeletedAtDesc(any(), any()))
                .thenReturn(List.of(
                        event("wm-c1", "agent1", CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, now),
                        event("wm-c2", "agent2", CustomerDeletionEvent.Direction.AGENT_DELETED_CUSTOMER, now)));

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.export("7d", null, null, "AGENT_DELETED_CUSTOMER", null, response);

        byte[] bytes = response.getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
            assertThat(sheet.getRow(1).getCell(4).getStringCellValue()).isEqualTo("员工删除客户");
        }
    }

    @Test
    @DisplayName("export — 同一客户多次删除同一员工只导出一行，次数列为聚合值")
    void exportAggregatesRepeatedDeletions() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        when(deletionRepo.findByDeletedAtBetweenOrderByDeletedAtDesc(any(), any()))
                .thenReturn(List.of(
                        event("wm-c1", "agent1", CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, now),
                        event("wm-c1", "agent1", CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, now.minusHours(1))));

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.export("7d", null, null, "CUSTOMER_DELETED_AGENT", null, response);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
            assertThat(sheet.getRow(0).getCell(5).getStringCellValue()).isEqualTo("删除次数");
            assertThat(sheet.getRow(1).getCell(5).getNumericCellValue()).isEqualTo(2.0);
        }
    }

    @Test
    @DisplayName("list — 注入今日推送接收人到模型")
    void listAddsTodayRecipientsToModel() {
        when(deletionRepo.findByDeletedAtBetweenOrderByDeletedAtDesc(any(), any()))
                .thenReturn(List.of());
        when(deletionReportService.getTodayRecipients()).thenReturn("boss1,boss2");

        ExtendedModelMap model = new ExtendedModelMap();
        controller.list("7d", null, null, "CUSTOMER_DELETED_AGENT", null, model);

        assertThat(model.getAttribute("todayRecipients")).isEqualTo("boss1,boss2");
    }

    @Test
    @DisplayName("list — 注入接收人姓名映射供前端 chips 回显")
    void listAddsTodayRecipientNamesToModel() {
        when(deletionRepo.findByDeletedAtBetweenOrderByDeletedAtDesc(any(), any()))
                .thenReturn(List.of());
        when(deletionReportService.getTodayRecipients()).thenReturn("u1,u2");
        when(employeeRepo.findByUseridIn(any())).thenReturn(List.of(
                Employee.builder().userid("u1").name("张三").build(),
                Employee.builder().userid("u2").name("李四").build()));

        ExtendedModelMap model = new ExtendedModelMap();
        controller.list("7d", null, null, "CUSTOMER_DELETED_AGENT", null, model);

        @SuppressWarnings("unchecked")
        Map<String, String> names = (Map<String, String>) model.getAttribute("todayRecipientNames");
        assertThat(names).containsEntry("u1", "张三").containsEntry("u2", "李四");
    }

    @Test
    @DisplayName("pushToday — 保存接收人并推送今日汇总后重定向")
    void pushTodaySavesRecipientsAndPushes() {
        when(deletionReportService.reportTodayWithLock("boss1,boss2"))
                .thenReturn(DeletionReportService.TodayPushResult.of(2, List.of()));

        RedirectAttributes ra = mock(RedirectAttributes.class);
        String view = controller.pushToday("boss1,boss2", ra);

        assertThat(view).isEqualTo("redirect:/deletion-records");
        verify(deletionReportService).saveTodayRecipients("boss1,boss2");
        verify(deletionReportService).reportTodayWithLock("boss1,boss2");
    }

    @Test
    @DisplayName("pushToday — 锁被占用时提示进行中")
    void pushTodayShowsBusyWhenLocked() {
        when(deletionReportService.reportTodayWithLock("boss1"))
                .thenReturn(DeletionReportService.TodayPushResult.lockBusy());

        RedirectAttributes ra = mock(RedirectAttributes.class);
        controller.pushToday("boss1", ra);

        verify(ra).addFlashAttribute(org.mockito.ArgumentMatchers.eq("message"),
                org.mockito.ArgumentMatchers.contains("进行中"));
    }

    @Test
    @DisplayName("pushToday — 部分账号失败时回显失败账号")
    void pushTodayEchoesFailedAccounts() {
        when(deletionReportService.reportTodayWithLock("boss1,boss2"))
                .thenReturn(DeletionReportService.TodayPushResult.of(1, List.of("boss2")));

        RedirectAttributes ra = mock(RedirectAttributes.class);
        controller.pushToday("boss1,boss2", ra);

        verify(ra).addFlashAttribute(org.mockito.ArgumentMatchers.eq("message"),
                org.mockito.ArgumentMatchers.contains("boss2"));
    }

    @Test
    @DisplayName("list — 注入员工单位映射与按单位汇总（降序）")
    void listAddsDeptMapAndSummary() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        when(deletionRepo.findByDeletedAtBetweenOrderByDeletedAtDesc(any(), any()))
                .thenReturn(List.of(
                        event("wm-c1", "agent1", CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, now),
                        event("wm-c2", "agent2", CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, now),
                        event("wm-c3", "agent3", CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, now)));
        when(employeeRepo.findByUseridIn(any())).thenReturn(List.of(
                Employee.builder().userid("agent1").name("张三").department("[1,2]").build(),
                Employee.builder().userid("agent2").name("李四").department("[1]").build(),
                Employee.builder().userid("agent3").name("王五").department("[3]").build()));
        when(departmentService.loadDeptIdNameMap()).thenReturn(Map.of(1L, "运营部", 3L, "客服部"));

        ExtendedModelMap model = new ExtendedModelMap();
        controller.list("7d", null, null, "CUSTOMER_DELETED_AGENT", null, model);

        @SuppressWarnings("unchecked")
        Map<String, String> deptMap = (Map<String, String>) model.getAttribute("employeeDeptMap");
        assertThat(deptMap).containsEntry("agent1", "运营部").containsEntry("agent3", "客服部");

        @SuppressWarnings("unchecked")
        Map<String, Long> summary = (Map<String, Long>) model.getAttribute("deptSummary");
        assertThat(summary).containsEntry("运营部", 2L).containsEntry("客服部", 1L);
        assertThat(summary.keySet()).containsExactly("运营部", "客服部");
    }

    @Test
    @DisplayName("export — 导出包含单位列")
    void exportIncludesDeptColumn() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        when(deletionRepo.findByDeletedAtBetweenOrderByDeletedAtDesc(any(), any()))
                .thenReturn(List.of(
                        event("wm-c1", "agent1", CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, now)));
        when(employeeRepo.findByUseridIn(any())).thenReturn(List.of(
                Employee.builder().userid("agent1").name("张三").department("[1]").build()));
        when(departmentService.loadDeptIdNameMap()).thenReturn(Map.of(1L, "运营部"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.export("7d", null, null, "CUSTOMER_DELETED_AGENT", null, response);

        byte[] bytes = response.getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(8).getStringCellValue()).isEqualTo("单位");
            assertThat(sheet.getRow(1).getCell(8).getStringCellValue()).isEqualTo("运营部");
            assertThat(sheet.getRow(0).getCell(9).getStringCellValue()).isEqualTo("来源");
        }
    }

    @Test
    @DisplayName("list — 反查活码来源（学校名）注入模型")
    void listAddsSourceMap() {
        LocalDateTime now = LocalDateTime.now();
        when(deletionRepo.findByDeletedAtBetweenOrderByDeletedAtDesc(any(), any()))
                .thenReturn(List.of(
                        event("wm-c1", "agent1", CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, now)));
        when(customerRepo.findByExternalUseridIn(any())).thenReturn(List.of(
                Customer.builder().externalUserid("wm-c1").name("张三").sourceQrId(1L).build()));
        when(qrCodeRepo.findAllById(any())).thenReturn(List.of(
                QrCode.builder().id(1L).schoolName("北京第一中学").build()));

        ExtendedModelMap model = new ExtendedModelMap();
        controller.list("7d", null, null, "CUSTOMER_DELETED_AGENT", null, model);

        @SuppressWarnings("unchecked")
        Map<String, String> sourceMap = (Map<String, String>) model.getAttribute("sourceMap");
        assertThat(sourceMap).containsEntry("wm-c1", "北京第一中学");
    }

    @Test
    @DisplayName("export — 来源列显示反查的学校名")
    void exportShowsSourceFromQr() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        when(deletionRepo.findByDeletedAtBetweenOrderByDeletedAtDesc(any(), any()))
                .thenReturn(List.of(
                        event("wm-c1", "agent1", CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, now)));
        when(customerRepo.findByExternalUseridIn(any())).thenReturn(List.of(
                Customer.builder().externalUserid("wm-c1").name("张三").sourceQrId(1L).build()));
        when(qrCodeRepo.findAllById(any())).thenReturn(List.of(
                QrCode.builder().id(1L).schoolName("北京第一中学").build()));

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.export("7d", null, null, "CUSTOMER_DELETED_AGENT", null, response);

        byte[] bytes = response.getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(9).getStringCellValue()).isEqualTo("北京第一中学");
        }
    }
}
