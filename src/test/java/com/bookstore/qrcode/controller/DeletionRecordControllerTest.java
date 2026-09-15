package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.CustomerDeletionEvent;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.CustomerDeletionEventRepository;
import com.bookstore.qrcode.repository.CustomerRepository;
import com.bookstore.qrcode.repository.EmployeeRepository;
import com.bookstore.qrcode.service.DeletionReportService;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
    @Mock private DeletionReportService deletionReportService;

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
        when(deletionRepo.findByDeletedAtBetweenOrderByDeletedAtDesc(any(), any(), any()))
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
        when(deletionRepo.findByDeletedAtBetweenOrderByDeletedAtDesc(any(), any(), any()))
                .thenReturn(List.of(
                        event("wm-c1", "agent1", CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT, now),
                        event("wm-c2", "agent2", CustomerDeletionEvent.Direction.AGENT_DELETED_CUSTOMER, now)));

        ExtendedModelMap model = new ExtendedModelMap();
        controller.list("7d", null, null, "CUSTOMER_DELETED_AGENT", null, model);

        @SuppressWarnings("unchecked")
        List<CustomerDeletionEvent> events = (List<CustomerDeletionEvent>) model.getAttribute("events");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getDirection()).isEqualTo(CustomerDeletionEvent.Direction.CUSTOMER_DELETED_AGENT);
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
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("删除时间");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("客户删除员工");
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
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("员工删除客户");
        }
    }

    @Test
    @DisplayName("list — 注入今日推送接收人到模型")
    void listAddsTodayRecipientsToModel() {
        when(deletionRepo.findByDeletedAtBetweenOrderByDeletedAtDesc(any(), any(), any()))
                .thenReturn(List.of());
        when(deletionReportService.getTodayRecipients()).thenReturn("boss1,boss2");

        ExtendedModelMap model = new ExtendedModelMap();
        controller.list("7d", null, null, "CUSTOMER_DELETED_AGENT", null, model);

        assertThat(model.getAttribute("todayRecipients")).isEqualTo("boss1,boss2");
    }

    @Test
    @DisplayName("pushToday — 保存接收人并推送今日汇总后重定向")
    void pushTodaySavesRecipientsAndPushes() {
        when(deletionReportService.reportTodayWithLock("boss1,boss2")).thenReturn(2);

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
                .thenReturn(DeletionReportService.LOCK_BUSY);

        RedirectAttributes ra = mock(RedirectAttributes.class);
        controller.pushToday("boss1", ra);

        verify(ra).addFlashAttribute(org.mockito.ArgumentMatchers.eq("message"),
                org.mockito.ArgumentMatchers.contains("进行中"));
    }
}
