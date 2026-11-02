package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.AgentAlert;
import com.bookstore.qrcode.repository.AgentRepository;
import com.bookstore.qrcode.repository.QrCodeRepository;
import com.bookstore.qrcode.service.AlertService;
import com.bookstore.qrcode.service.AlertTriageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

import static com.bookstore.qrcode.entity.AgentAlert.AlertStatus.open;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AlertController 告警中心")
class AlertControllerTest {

    @Mock AlertService alertService;
    @Mock AgentRepository agentRepo;
    @Mock QrCodeRepository qrCodeRepo;
    @Mock AlertTriageService triageService;
    @InjectMocks AlertController controller;

    private static AlertTriageService.TriageSummary emptySummary() {
        return new AlertTriageService.TriageSummary(List.of(), List.of(), 0, 0);
    }

    /** 分页查询（size=20）与卡片统计（size=2000）使用不同的 Pageable，借此区分两次 findAlerts。 */
    private static Pageable pageableOfSize(int size) {
        return argThat(p -> p != null && p.getPageSize() == size);
    }

    @Test
    @DisplayName("未传 status → 默认只查 open")
    void shouldDefaultToOpen() {
        when(alertService.findAlerts(eq(open), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Page.empty());
        when(alertService.getAlertStats()).thenReturn(Map.of());
        when(triageService.buildSummary(any())).thenReturn(emptySummary());

        ExtendedModelMap model = new ExtendedModelMap();
        controller.list(null, null, null, null, null, null, 0, 20, model);

        assertThat(model.get("statusFilter")).isEqualTo("open");
        verify(alertService).findAlerts(eq(open), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), pageableOfSize(20));
    }

    @Test
    @DisplayName("显式传空 status → 查全部")
    void shouldAllowAllStatus() {
        when(alertService.findAlerts(isNull(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Page.empty());
        when(alertService.findAlerts(eq(open), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Page.empty());
        when(alertService.getAlertStats()).thenReturn(Map.of());
        when(triageService.buildSummary(any())).thenReturn(emptySummary());

        ExtendedModelMap model = new ExtendedModelMap();
        controller.list("", null, null, null, null, null, 0, 20, model);

        assertThat(model.get("statusFilter")).isNull();
        verify(alertService).findAlerts(isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), pageableOfSize(20));
    }

    @Test
    @DisplayName("列表页写入 triage / typeOptions / typeLabelMap")
    void shouldExposeTriageModelAttributes() {
        when(alertService.findAlerts(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Page.empty());
        when(alertService.getAlertStats()).thenReturn(Map.of());
        when(triageService.buildSummary(any())).thenReturn(emptySummary());

        ExtendedModelMap model = new ExtendedModelMap();
        controller.list(open.name(), null, null, null, null, null, 0, 20, model);

        assertThat(model.get("triage")).isEqualTo(emptySummary());
        assertThat((List<?>) model.get("typeOptions")).hasSizeGreaterThan(0);
        @SuppressWarnings("unchecked")
        Map<String, String> typeLabelMap = (Map<String, String>) model.get("typeLabelMap");
        assertThat(typeLabelMap).containsEntry("add_fail", "加好友失败");
    }

    @Test
    @DisplayName("归档端点调用 archiveStale 并提示数量")
    void shouldArchiveStale() {
        when(alertService.archiveStale()).thenReturn(12);
        RedirectAttributes ra = mock(RedirectAttributes.class);

        String view = controller.archiveStale(null, 0, null, null, null, null, null, null, ra);

        verify(alertService).archiveStale();
        verify(ra).addFlashAttribute(eq("message"), contains("12"));
        assertThat(view).startsWith("redirect:/alerts");
    }
}
